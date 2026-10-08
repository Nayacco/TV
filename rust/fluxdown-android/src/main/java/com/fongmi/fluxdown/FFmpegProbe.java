package com.fongmi.fluxdown;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Collects bounded local-file metadata without creating outputs or changing the input. */
public final class FFmpegProbe {
    private static final long TIMEOUT_MS = 5_000;
    private static final long OUTPUT_DRAIN_MS = 250;
    private static final int OUTPUT_LIMIT_BYTES = 8 * 1024;
    // Do not allow playlists, concat, image sequences or network protocols. A rejected format
    // is a diagnostic limitation, not proof that the cached video is invalid.
    private static final String FORMATS = "mov,mpegts,matroska,avi,flv,mpeg,ogg,asf,mp3,aac,wav,flac,"
            + "ac3,eac3,amr,ape,au,nut,rm,ivf,h264,hevc,vc1";

    private FFmpegProbe() {
    }

    public static String inspect(File executable, File media) {
        return inspect(executable, media,
                command -> new ProcessBuilder(command).redirectErrorStream(true).start(),
                TIMEOUT_MS, OUTPUT_LIMIT_BYTES);
    }

    interface ProcessLauncher {
        Process start(List<String> command) throws IOException;
    }

    // Package-private seam makes timeout, output limits and cancellation testable without
    // requiring an Android FFmpeg executable on a developer's workstation.
    static String inspect(File executable, File media, ProcessLauncher launcher,
                          long timeoutMillis, int outputLimitBytes) {
        if (Thread.currentThread().isInterrupted()) return "FFmpeg probe: interrupted (not started)";
        if (executable == null || !executable.isFile() || !executable.canExecute()) {
            return "FFmpeg probe: unavailable (packaged executable is missing or not executable)";
        }
        if (media == null || !media.isFile() || !media.canRead()) {
            return "FFmpeg probe: unavailable (cached file is missing or unreadable)";
        }
        if (timeoutMillis <= 0 || outputLimitBytes <= 0) {
            return "FFmpeg probe: unavailable (invalid diagnostic limits)";
        }
        List<String> command = Arrays.asList(executable.getAbsolutePath(),
                "-hide_banner", "-nostdin", "-v", "info",
                "-protocol_whitelist", "file", "-format_whitelist", FORMATS,
                "-probesize", "1048576", "-analyzeduration", "2000000",
                "-i", media.getAbsolutePath());
        Process process;
        try {
            process = launcher.start(command);
        } catch (IOException | RuntimeException error) {
            return "FFmpeg probe: could not start (" + describe(error) + ")";
        }

        Capture capture = new Capture(outputLimitBytes);
        Thread reader = new Thread(() -> capture.read(process.getInputStream()), "ffmpeg-probe-output");
        // Some process/pipe implementations can retain stdout after process exit. Never let
        // their EOF behavior make cache completion or app shutdown wait indefinitely.
        reader.setDaemon(true);
        reader.start();
        String status;
        Integer exit = null;
        boolean interrupted = false;
        String terminationError = null;
        try {
            process.getOutputStream().close();
            long started = System.nanoTime();
            while (true) {
                try {
                    exit = process.exitValue();
                    status = "exited " + exit;
                    break;
                } catch (IllegalThreadStateException running) {
                    if ((System.nanoTime() - started) / 1_000_000 >= timeoutMillis) {
                        status = "timed out after " + timeoutMillis + " ms";
                        break;
                    }
                    Thread.sleep(Math.min(25, timeoutMillis));
                }
            }
        } catch (InterruptedException error) {
            interrupted = true;
            status = "interrupted";
        } catch (IOException | RuntimeException error) {
            status = "could not inspect (" + describe(error) + ")";
        } finally {
            // Let an exited process's pipe drain before destroy(), which can close the pipe
            // immediately on some platforms. Running/timed-out processes must be stopped first.
            if (exit == null) terminationError = destroy(process);
            if (!interrupted) {
                try {
                    reader.join(OUTPUT_DRAIN_MS);
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (exit != null) terminationError = destroy(process);
            if (interrupted) Thread.currentThread().interrupt();
        }
        StringBuilder result = new StringBuilder("FFmpeg probe: ").append(status);
        if (interrupted && !"interrupted".equals(status)) result.append("; output capture interrupted");
        result.append("\nRead-only metadata probe; no output file requested.");
        if (exit != null) {
            result.append("\nExit 1 is expected when readable input has no output requested; "
                    + "the exit code alone does not establish whether the video is valid.");
        }
        if (reader.isAlive()) result.append("\nOutput drain stopped waiting after ").append(OUTPUT_DRAIN_MS).append(" ms.");
        if (terminationError != null) result.append("\nChild termination error: ").append(terminationError);
        result.append(capture.summary());
        return result.toString();
    }

    private static String destroy(Process process) {
        try {
            process.destroy();
            return null;
        } catch (RuntimeException error) {
            return describe(error);
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        if (message != null && message.length() > 512) message = message.substring(0, 512) + " [truncated]";
        return error.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private static final class Capture {
        private final int limit;
        private final ByteArrayOutputStream output;
        private boolean truncated;
        private String error;

        private Capture(int limit) {
            this.limit = limit;
            this.output = new ByteArrayOutputStream(Math.min(limit, 4096));
        }

        private void read(InputStream input) {
            try (InputStream source = input) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    synchronized (this) {
                        int retained = Math.min(count, limit - output.size());
                        if (retained > 0) output.write(buffer, 0, retained);
                        if (retained < count) truncated = true;
                    }
                }
            } catch (IOException | RuntimeException failure) {
                synchronized (this) {
                    error = describe(failure);
                }
            }
        }

        private synchronized String summary() {
            StringBuilder result = new StringBuilder();
            if (output.size() > 0) {
                result.append("\nFFmpeg output:\n").append(new String(output.toByteArray(), StandardCharsets.UTF_8).trim());
            } else {
                result.append("\nFFmpeg output: (none captured)");
            }
            if (truncated) result.append("\n[FFmpeg output truncated at ").append(limit).append(" bytes]");
            if (error != null) result.append("\nOutput capture error: ").append(error);
            return result.toString();
        }
    }
}
