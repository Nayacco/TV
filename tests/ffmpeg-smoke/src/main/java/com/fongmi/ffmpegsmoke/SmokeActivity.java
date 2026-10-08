package com.fongmi.ffmpegsmoke;

import android.app.Activity;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.system.Os;
import android.util.Log;
import android.widget.TextView;

import com.fongmi.fluxdown.FFmpegRuntime;
import com.fongmi.fluxdown.FFmpegProbe;
import com.fongmi.fluxdown.FFmpegTsNormalizer;
import com.fongmi.fluxdown.PngTsCleaner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Executes packaged binaries as an ordinary target-37 app, not as adb's shell user. */
public final class SmokeActivity extends Activity {
    private static final String TAG = "FFmpegSmoke";
    private static final long PROCESS_TIMEOUT_MS = 10_000;
    private final StringBuilder summary = new StringBuilder();
    private File work;
    private int invocation;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView status = new TextView(this);
        status.setText("Running packaged Android FFmpeg smoke test...");
        setContentView(status);
        new Thread(() -> {
            String result;
            try {
                verify();
                result = "SUCCESS\n" + summary;
                Log.i(TAG, result);
            } catch (Throwable error) {
                StringWriter trace = new StringWriter();
                error.printStackTrace(new PrintWriter(trace));
                result = "FAILURE\n" + summary + trace;
                Log.e(TAG, result, error);
            }
            try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "result.txt"))) {
                output.write(result.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            } catch (IOException error) {
                Log.e(TAG, "Could not persist smoke-test result", error);
            }
            String finalResult = result;
            runOnUiThread(() -> status.setText(finalResult));
        }, "ffmpeg-smoke").start();
    }

    private void verify() throws Exception {
        work = new File(getFilesDir(), "fixtures");
        require(work.isDirectory() || work.mkdirs(), "Cannot create fixture directory");
        String nativeDir = getApplicationInfo().nativeLibraryDir;
        File ffmpeg = FFmpegRuntime.prepare(new File(getFilesDir(), "fluxdown").getAbsolutePath(), nativeDir);
        require(ffmpeg.getCanonicalPath().equals(new File(nativeDir, "libffmpeg.so").getCanonicalPath()),
                "Runtime link does not resolve to the packaged executable");
        require(FFmpegRuntime.prepare(new File(getFilesDir(), "fluxdown").getAbsolutePath(), nativeDir).equals(ffmpeg),
                "Runtime preparation must be idempotent");
        Os.remove(ffmpeg.getAbsolutePath());
        Os.symlink(new File(nativeDir, "previous-installation-libffmpeg.so").getAbsolutePath(), ffmpeg.getAbsolutePath());
        ffmpeg = FFmpegRuntime.prepare(new File(getFilesDir(), "fluxdown").getAbsolutePath(), nativeDir);
        require(ffmpeg.getCanonicalPath().equals(new File(nativeDir, "libffmpeg.so").getCanonicalPath()),
                "Runtime did not replace the previous installation's dangling executable link");
        requireUnmanagedPathPreserved(nativeDir);
        String version = run(ffmpeg, "-version");
        require(version.startsWith("ffmpeg version "), "Packaged FFmpeg did not report its version");
        summary.append("APK executable via app-private managed symlink: OK\n");

        File video = new File(work, "video.mp4");
        File audio = new File(work, "audio.m4a");
        File combined = new File(work, "combined.mp4");
        run(ffmpeg, "-y", "-nostdin", "-f", "lavfi", "-i", "color=c=black:s=64x64:r=10",
                "-t", "1", "-an", "-c:v", "mpeg4", "-threads", "1", "-pix_fmt", "yuv420p", video.getAbsolutePath());
        run(ffmpeg, "-y", "-nostdin", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                "-t", "1", "-vn", "-c:a", "aac", "-threads", "1", audio.getAbsolutePath());
        // Keep these stream maps and mux arguments identical to FluxDown's two-input merge.
        run(ffmpeg, "-y", "-i", video.getAbsolutePath(), "-i", audio.getAbsolutePath(),
                "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-movflags", "+faststart",
                "-f", "mp4", combined.getAbsolutePath());
        requireMp4(combined);
        requireAndroidTracks(combined);
        summary.append("Independent MPEG4 video + AAC audio -> one MP4, readable tracks/samples: OK\n");
        requireReadOnlyProbe(ffmpeg, combined);

        File ts = new File(work, "input.ts");
        File remuxed = new File(work, "remuxed.mp4");
        run(ffmpeg, "-y", "-nostdin", "-f", "lavfi", "-i", "color=c=black:s=64x64:r=10",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "1",
                "-c:v", "mpeg2video", "-c:a", "aac", "-threads", "1", "-pix_fmt", "yuv420p",
                "-f", "mpegts", ts.getAbsolutePath());
        try (FileInputStream input = new FileInputStream(ts)) {
            require(input.read() == 0x47, "Generated TS fixture has no MPEG-TS sync byte");
        }
        // Same optional maps as FluxDown's single-input TS remux; do not re-encode.
        run(ffmpeg, "-y", "-i", ts.getAbsolutePath(), "-map", "0:v?", "-map", "0:a?",
                "-c", "copy", "-movflags", "+faststart", "-f", "mp4", remuxed.getAbsolutePath());
        requireMp4(remuxed);
        // Explicit maps prove both tracks exist; null mux traverses the whole file without
        // transcoding or relying on Android's availability of an MPEG2 decoder.
        run(ffmpeg, "-v", "error", "-i", remuxed.getAbsolutePath(), "-map", "0:v:0", "-map", "0:a:0",
                "-c", "copy", "-f", "null", "-");
        summary.append("MPEG2 video + AAC in TS -> actual MP4 via stream copy, both tracks: OK\n");
        String tsProbe = FFmpegProbe.inspect(ffmpeg, ts);
        require(tsProbe.contains("Input #0, mpegts") && tsProbe.contains("Video:") && tsProbe.contains("Audio:"),
                "Production metadata probe did not identify TS streams: " + tsProbe);
        summary.append("Production read-only metadata probe identifies actual TS container/streams: OK\n");
        requirePngWrappedTs(ffmpeg, ts);
    }

    private void requirePngWrappedTs(File ffmpeg, File ts) throws Exception {
        File wrapped = new File(work, "png-wrapped.ts");
        File cleaned = new File(work, "png-cleaned.ts");
        require(ts.length() % 188 == 0 && ts.length() >= 10 * 188, "TS fixture must contain complete packets");
        long split = ts.length() / 188 / 2 * 188;
        try (FileInputStream input = new FileInputStream(ts); FileOutputStream output = new FileOutputStream(wrapped)) {
            output.write(pngPrefix(126));
            copyBytes(input, output, split);
            output.write(pngPrefix(211));
            copyBytes(input, output, ts.length() - split);
        }
        byte[] wrappedDigest = fileDigest(wrapped);
        require(PngTsCleaner.hasPngPrefix(wrapped), "Wrapped fixture must exercise the production PNG detection");
        String before = FFmpegProbe.inspect(ffmpeg, wrapped);
        require(before.contains("png_pipe") && before.contains("not on whitelist"),
                "Wrapped fixture did not reproduce the original FFmpeg detection failure: " + before);
        PngTsCleaner.clean(wrapped, cleaned);
        require(Arrays.equals(fileDigest(ts), fileDigest(cleaned)), "Cleaner lost or altered TS packet bytes");
        require(Arrays.equals(wrappedDigest, fileDigest(wrapped)), "Cleaner changed its original input");

        try (FFmpegTsNormalizer.Prepared prepared = FFmpegTsNormalizer.prepare(ffmpeg, wrapped,
                SmokeActivity::requireAndroidTracks)) {
            require(Arrays.equals(wrappedDigest, fileDigest(wrapped)), "Prepare replaced the source before commit");
            requireAndroidTracks(prepared.getOutput());
            run(ffmpeg, "-v", "error", "-xerror", "-f", "mpegts", "-i", prepared.getOutput().getAbsolutePath(),
                    "-map", "0:v:0", "-map", "0:a:0", "-c", "copy", "-f", "null", "-");
            prepared.commit();
        }
        require(!PngTsCleaner.hasPngPrefix(wrapped), "Committed media still begins with a PNG prefix");
        requireAndroidTracks(wrapped);
        String after = FFmpegProbe.inspect(ffmpeg, wrapped);
        require(after.contains("Input #0, mpegts") && after.contains("Video:") && after.contains("Audio:"),
                "Normalized file does not contain both TS streams: " + after);
        summary.append("Production PNG multi-segment cleanup: byte-identical TS, forced stream-copy remux, Android tracks/samples, commit: OK\n");

        File truncated = new File(work, "png-truncated.ts");
        try (FileInputStream input = new FileInputStream(ts); FileOutputStream output = new FileOutputStream(truncated)) {
            output.write(pngPrefix(126));
            copyBytes(input, output, ts.length() - 1);
        }
        byte[] truncatedDigest = fileDigest(truncated);
        int filesBefore = work.list().length;
        boolean rejected = false;
        try (FFmpegTsNormalizer.Prepared ignored = FFmpegTsNormalizer.prepare(ffmpeg, truncated,
                SmokeActivity::requireAndroidTracks)) {
            throw new IOException("Normalizer accepted a truncated TS packet");
        } catch (IOException expected) {
            require(expected.getMessage().contains("Truncated TS packet"), "Unexpected rejection: " + expected);
            rejected = true;
        }
        require(rejected && Arrays.equals(truncatedDigest, fileDigest(truncated)), "Rejected input was changed");
        require(work.list().length == filesBefore, "Failed normalization left temporary files");
        summary.append("Production truncated TS rejection: unchanged original, no temporary-file leak: OK\n");
    }

    private static byte[] pngPrefix(int length) {
        byte[] prefix = new byte[length];
        byte[] header = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0, 0, 0, 0x0d, 0x49, 0x48, 0x44, 0x52,
                0, 0, 3, 0x20, 0, 0, 3, 0x20, 8, 3, 0, 0, 0, (byte) 0xec, (byte) 0xae, (byte) 0xf6};
        System.arraycopy(header, 0, prefix, 0, header.length);
        return prefix;
    }

    private static void copyBytes(InputStream input, FileOutputStream output, long remaining) throws IOException {
        byte[] buffer = new byte[4096];
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            require(count > 0, "Fixture input was truncated");
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }

    private void requireReadOnlyProbe(File ffmpeg, File combined) throws IOException {
        byte[] before = fileDigest(combined);
        int filesBefore = work.list().length;
        String probe = FFmpegProbe.inspect(ffmpeg, combined);
        require(probe.startsWith("FFmpeg probe: exited 1"), "Input-only probe exit must be reported neutrally: " + probe);
        require(probe.contains("Input #0, mov,mp4") && probe.contains("Video:") && probe.contains("Audio:"),
                "Production probe did not report MP4's container and both streams: " + probe);
        require(probe.contains("Exit 1 is expected"), "A readable input-only probe must not be labelled a failure");
        require(Arrays.equals(before, fileDigest(combined)), "Metadata probe changed cached media");
        require(work.list().length == filesBefore, "Metadata probe created an output file");

        File malformed = new File(work, "malformed.mp4");
        try (FileOutputStream output = new FileOutputStream(malformed)) {
            output.write("<html>not video</html>".getBytes(StandardCharsets.UTF_8));
        }
        byte[] malformedBefore = fileDigest(malformed);
        filesBefore = work.list().length;
        String rejected = FFmpegProbe.inspect(ffmpeg, malformed);
        require(rejected.startsWith("FFmpeg probe: exited 1") && rejected.contains("FFmpeg output:"),
                "Malformed media probe did not capture FFmpeg's diagnostic output: " + rejected);
        require(rejected.contains("Invalid data found") || rejected.contains("moov atom not found"),
                "Malformed media probe lacks its input error: " + rejected);
        require(Arrays.equals(malformedBefore, fileDigest(malformed)), "Probe modified malformed input");
        require(work.list().length == filesBefore, "Malformed media probe created an output file");
        summary.append("Production read-only probe: MP4 streams, neutral exit 1, malformed-file diagnostics, unchanged inputs/no outputs: OK\n");
    }

    private static byte[] fileDigest(File file) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 unavailable", error);
        }
    }

    private void requireUnmanagedPathPreserved(String nativeDir) throws IOException {
        File data = new File(getFilesDir(), "unmanaged-component");
        File bin = new File(data, "bin");
        require(bin.mkdirs(), "Cannot create unmanaged-component test directory");
        File existing = new File(bin, "ffmpeg");
        try (FileOutputStream output = new FileOutputStream(existing)) {
            output.write(42);
        }
        boolean rejected = false;
        try {
            FFmpegRuntime.prepare(data.getAbsolutePath(), nativeDir);
        } catch (IOException expected) {
            rejected = true;
        }
        require(rejected, "Runtime overwrote an unmanaged executable path");
        try (FileInputStream input = new FileInputStream(existing)) {
            require(existing.length() == 1 && input.read() == 42, "Unmanaged file was modified");
        }
        summary.append("Runtime preparation idempotence, upgrade link refresh, unmanaged-file preservation: OK\n");
    }

    private String run(File executable, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.addAll(Arrays.asList(arguments));
        File log = new File(work, String.format(java.util.Locale.ROOT, "process-%02d.log", ++invocation));
        // File redirects and waitFor(timeout) were only added in API26. Drain the merged
        // pipe concurrently and poll exitValue so the test remains compatible with API24.
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        AtomicReference<IOException> loggingError = new AtomicReference<>();
        Thread logger = new Thread(() -> {
            try (InputStream input = process.getInputStream(); FileOutputStream output = new FileOutputStream(log)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            } catch (IOException error) {
                loggingError.set(error);
            }
        }, "ffmpeg-smoke-output");
        logger.setDaemon(true);
        logger.start();
        try {
            process.getOutputStream().close();
            long deadline = android.os.SystemClock.elapsedRealtime() + PROCESS_TIMEOUT_MS;
            int exit;
            while (true) {
                try {
                    exit = process.exitValue();
                    break;
                } catch (IllegalThreadStateException running) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        throw new IOException("Timed out running " + command + "\n" + readLog(log));
                    }
                    Thread.sleep(50);
                }
            }
            logger.join(1000);
            require(!logger.isAlive(), "Process output stream did not close: " + command);
            if (loggingError.get() != null) throw new IOException("Cannot capture process output", loggingError.get());
            String output = readLog(log);
            require(exit == 0, "Command failed (" + exit + "): " + command + "\n" + output);
            return output;
        } finally {
            process.destroy();
        }
    }

    private static String readLog(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while (output.size() < 32 * 1024 && (count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void requireMp4(File file) throws IOException {
        require(file.length() > 12, "MP4 output is empty: " + file);
        byte[] header = new byte[8];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int count = input.read(header, offset, header.length - offset);
                require(count > 0, "MP4 output is truncated: " + file);
                offset += count;
            }
        }
        require(header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p',
                "Output extension is .mp4 but content has no MP4 ftyp box: " + file);
    }

    private static void requireAndroidTracks(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            boolean video = false;
            boolean audio = false;
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                MediaFormat format = extractor.getTrackFormat(index);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !(mime.startsWith("video/") || mime.startsWith("audio/"))) continue;
                extractor.selectTrack(index);
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                require(extractor.readSampleData(ByteBuffer.allocate(16 * 1024), 0) > 0,
                        "Merged track contains no readable sample: " + mime);
                extractor.unselectTrack(index);
                if (mime.startsWith("video/")) video = true;
                if (mime.startsWith("audio/")) audio = true;
            }
            require(video && audio, "Completed media must have both video and audio tracks");
        } finally {
            extractor.release();
        }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
