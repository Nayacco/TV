package com.fongmi.fluxdown;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Prepares an independently validated TS file; the caller alone decides whether to commit it. */
public final class FFmpegTsNormalizer {
    private static final long TIMEOUT_MS = 20 * 60 * 1000L;
    private static final long OUTPUT_DRAIN_MS = 250;
    private static final int OUTPUT_LIMIT_BYTES = 8 * 1024;
    private static final long SPACE_MARGIN_BYTES = 16 * 1024 * 1024;

    private FFmpegTsNormalizer() {
    }

    public interface OutputValidator {
        void validate(File file) throws IOException;
    }

    interface ProcessLauncher {
        Process start(List<String> command) throws IOException;
    }

    interface FileMover {
        boolean move(File from, File to);
    }

    public static Prepared prepare(File executable, File source, OutputValidator validator) throws IOException {
        return prepare(executable, source, validator,
                command -> new ProcessBuilder(command).redirectErrorStream(true).start(),
                TIMEOUT_MS, OUTPUT_LIMIT_BYTES);
    }

    // Inject only the operating-system process boundary; cleaning and file validation stay real.
    static Prepared prepare(File executable, File source, OutputValidator validator,
                            ProcessLauncher launcher, long timeoutMillis, int outputLimitBytes) throws IOException {
        checkInterrupted();
        if (executable == null || !executable.isFile() || !executable.canExecute()) {
            throw new IOException("Packaged Android FFmpeg is missing or not executable");
        }
        if (validator == null || launcher == null || timeoutMillis <= 0 || outputLimitBytes <= 0) {
            throw new IOException("Invalid TS normalization configuration");
        }
        File input = requireSource(source);
        File parent = input.getParentFile();
        long originalLength = input.length();
        long originalModified = input.lastModified();
        long usable = parent.getUsableSpace();
        if (originalLength > (Long.MAX_VALUE - SPACE_MARGIN_BYTES) / 2
                || usable < originalLength * 2 + SPACE_MARGIN_BYTES) {
            throw new IOException("Not enough free space to normalize cached TS: need room for cleaned and remuxed "
                    + "copies in addition to the original (free=" + usable + " bytes, original="
                    + originalLength + " bytes)");
        }

        File cleaned = uniquePath(parent, ".fluxdown-cleaned-");
        File output = null;
        boolean retained = false;
        IOException failure = null;
        try {
            PngTsCleaner.cleanForRemux(input, cleaned);
            checkInterrupted();
            requirePrivateFile(cleaned, parent, input);
            output = File.createTempFile(".fluxdown-remux-", ".ts", parent);
            requirePrivateFile(output, parent, input);
            List<String> command = Arrays.asList(executable.getAbsolutePath(),
                    "-hide_banner", "-nostdin", "-v", "error", "-xerror", "-y",
                    "-protocol_whitelist", "file", "-format_whitelist", "mpegts",
                    "-f", "mpegts", "-i", cleaned.getAbsolutePath(),
                    "-map", "0:v:0", "-map", "0:a:0", "-c", "copy", "-f", "mpegts", output.getAbsolutePath());
            run(command, launcher, timeoutMillis, outputLimitBytes);
            checkInterrupted();
            requirePrivateFile(output, parent, input);
            if (output.length() <= 0) throw new IOException("FFmpeg produced an empty normalized TS file");
            validator.validate(output);
            checkInterrupted();
            requirePrivateFile(output, parent, input);
            if (output.length() <= 0) throw new IOException("Validated normalized TS file is empty");
            requireUnchangedSource(input, originalLength, originalModified);
            Prepared prepared = new Prepared(input, output, originalLength, originalModified);
            retained = true;
            return prepared;
        } catch (IOException error) {
            failure = error;
            throw error;
        } catch (RuntimeException error) {
            failure = new IOException("Unable to normalize cached TS", error);
            throw failure;
        } finally {
            IOException cleanupError = deleteTemporary(cleaned, parent);
            if (!retained && output != null) {
                IOException outputError = deleteTemporary(output, parent);
                if (cleanupError == null) cleanupError = outputError;
                else if (outputError != null) cleanupError.addSuppressed(outputError);
            }
            if (cleanupError != null) {
                if (failure != null) failure.addSuppressed(cleanupError);
                else {
                    // Never leak the prepared output when its clean-input cleanup failed.
                    if (retained && output != null) {
                        IOException outputError = deleteTemporary(output, parent);
                        if (outputError != null) cleanupError.addSuppressed(outputError);
                    }
                    throw cleanupError;
                }
            }
        }
    }

    public static final class Prepared implements Closeable {
        private final File source;
        private final File output;
        private final long sourceLength;
        private final long sourceModified;
        private final long outputLength;
        private final long outputModified;
        private boolean committed;
        private boolean closed;

        private Prepared(File source, File output, long sourceLength, long sourceModified) {
            this.source = source;
            this.output = output;
            this.sourceLength = sourceLength;
            this.sourceModified = sourceModified;
            this.outputLength = output.length();
            this.outputModified = output.lastModified();
        }

        public File getOutput() {
            return output;
        }

        /** Call only after the owner has rechecked that this task is still active and not cancelled. */
        public void commit() throws IOException {
            commit(File::renameTo);
        }

        // A filesystem boundary seam for deterministic rollback tests using real source/backup files.
        synchronized void commit(FileMover mover) throws IOException {
            if (committed) return;
            if (closed) throw new IOException("Prepared TS normalization has already been closed");
            if (mover == null) throw new IOException("Missing normalization file mover");
            checkInterrupted();
            requireUnchangedSource(source, sourceLength, sourceModified);
            requirePrivateFile(output, source.getParentFile(), source);
            if (output.length() != outputLength || output.lastModified() != outputModified) {
                throw new IOException("Validated normalized TS changed before commit; original was not replaced");
            }
            File backup = uniquePath(source.getParentFile(), ".fluxdown-original-");
            try {
                if (!mover.move(source, backup)) {
                    throw new IOException("Unable to back up original TS; original was not replaced");
                }
            } catch (RuntimeException error) {
                throw new IOException("Unable to back up original TS; original was not replaced", error);
            }
            try {
                if (!mover.move(output, source)) throw new IOException("Unable to install normalized TS");
            } catch (IOException | RuntimeException error) {
                boolean restored = false;
                try {
                    // Never overwrite a replacement introduced by another actor after the first rename.
                    restored = !source.exists() && mover.move(backup, source);
                } catch (RuntimeException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw new IOException(restored
                        ? "Unable to install normalized TS; original was restored"
                        : "Unable to install normalized TS; original is preserved at " + backup, error);
            }
            committed = true;
            closed = true;
            IOException error = deleteTemporary(backup, source.getParentFile());
            if (error != null) {
                throw new IOException("Normalized TS was installed, but original backup could not be removed: " + backup, error);
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) return;
            IOException error = deleteTemporary(output, source.getParentFile());
            if (error != null) throw error;
            closed = true;
        }
    }

    private static File requireSource(File file) throws IOException {
        if (file == null) throw new IOException("Missing source TS file");
        File absolute = file.getAbsoluteFile();
        File canonical = file.getCanonicalFile();
        if (!canonical.equals(absolute) || !canonical.isFile() || !canonical.canRead() || canonical.length() <= 0) {
            throw new IOException("Source TS must be a nonempty readable regular file without symlinks");
        }
        return canonical;
    }

    private static void requireUnchangedSource(File source, long length, long modified) throws IOException {
        requireSource(source);
        if (source.length() != length || source.lastModified() != modified) {
            throw new IOException("Source TS changed while normalization was running; original was not replaced");
        }
    }

    private static File uniquePath(File parent, String prefix) throws IOException {
        File file = new File(parent, prefix + UUID.randomUUID() + ".ts");
        if (!file.getCanonicalFile().equals(file) || file.exists()) {
            throw new IOException("Unable to reserve a private TS normalization path");
        }
        return file;
    }

    private static void requirePrivateFile(File file, File parent, File source) throws IOException {
        if (!file.getAbsoluteFile().getParentFile().equals(parent) || !file.getCanonicalFile().equals(file)
                || file.equals(source) || !file.isFile() || !file.canRead()) {
            throw new IOException("Normalization output must be a private regular file beside the source");
        }
    }

    private static IOException deleteTemporary(File file, File parent) {
        try {
            if (!file.getAbsoluteFile().getParentFile().equals(parent) || !parent.getCanonicalFile().equals(parent)) {
                return new IOException("Refusing to clean a normalization path outside the original directory");
            }
            // Delete only this random directory entry, never follow it or recursively delete a directory.
            if (file.exists() && !file.delete()) return new IOException("Unable to remove TS normalization temporary: " + file);
            return null;
        } catch (IOException | RuntimeException error) {
            return new IOException("Unable to clean TS normalization temporary: " + file, error);
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("TS normalization interrupted");
    }

    private static void run(List<String> command, ProcessLauncher launcher,
                            long timeoutMillis, int outputLimitBytes) throws IOException {
        checkInterrupted();
        Process process = launcher.start(command);
        Capture capture;
        Thread reader;
        try {
            capture = new Capture(outputLimitBytes);
            reader = new Thread(() -> capture.read(process), "ffmpeg-ts-normalizer-output");
            reader.setDaemon(true);
            reader.start();
        } catch (RuntimeException | Error error) {
            destroy(process);
            throw error;
        }
        Integer exit = null;
        String failure = null;
        boolean interrupted = false;
        try {
            process.getOutputStream().close();
            long started = System.nanoTime();
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try {
                    exit = process.exitValue();
                    break;
                } catch (IllegalThreadStateException running) {
                    if ((System.nanoTime() - started) / 1_000_000 >= timeoutMillis) {
                        failure = "FFmpeg TS normalization timed out after " + timeoutMillis + " ms";
                        break;
                    }
                    Thread.sleep(Math.min(25, timeoutMillis));
                }
            }
        } catch (InterruptedException error) {
            interrupted = true;
        } catch (IOException | RuntimeException error) {
            failure = "FFmpeg TS normalization process failed: " + describe(error);
        } finally {
            if (exit == null) destroy(process);
            if (!interrupted) {
                try {
                    reader.join(OUTPUT_DRAIN_MS);
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (exit != null && exit == 0 && !interrupted && failure == null) {
                if (reader.isAlive()) failure = "FFmpeg TS normalization output did not drain within " + OUTPUT_DRAIN_MS + " ms";
                else if (capture.failed()) failure = "FFmpeg TS normalization output capture failed";
            }
            destroy(process);
            if (reader.isAlive()) reader.interrupt();
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (interrupted) throw new InterruptedIOException("TS normalization interrupted" + capture.summary());
        if (failure != null) throw new IOException(failure + capture.summary());
        if (exit == null || exit != 0) {
            throw new IOException("FFmpeg TS normalization exited with status " + exit + capture.summary());
        }
    }

    private static void destroy(Process process) {
        try {
            process.destroy();
        } catch (RuntimeException ignored) {
            // A failed normalization never commits its output.
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        if (message != null && message.length() > 512) message = message.substring(0, 512) + " [truncated]";
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static final class Capture {
        private final int limit;
        private final ByteArrayOutputStream output;
        private boolean truncated;
        private String failure;

        private Capture(int limit) {
            this.limit = limit;
            output = new ByteArrayOutputStream(Math.min(4096, limit));
        }

        private void read(Process process) {
            try (InputStream source = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    synchronized (this) {
                        int retained = Math.min(count, limit - output.size());
                        if (retained > 0) output.write(buffer, 0, retained);
                        if (retained < count) truncated = true;
                    }
                }
            } catch (IOException | RuntimeException error) {
                synchronized (this) {
                    failure = describe(error);
                }
            }
        }

        private synchronized boolean failed() {
            return failure != null;
        }

        private synchronized String summary() {
            String log = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            return (log.isEmpty() ? "" : "\nFFmpeg output:\n" + log)
                    + (truncated ? "\n[FFmpeg output truncated at " + limit + " bytes]" : "")
                    + (failure == null ? "" : "\nOutput capture error: " + failure);
        }
    }
}
