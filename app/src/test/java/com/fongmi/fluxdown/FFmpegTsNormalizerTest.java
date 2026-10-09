package com.fongmi.fluxdown;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;

public class FFmpegTsNormalizerTest {
    private static final File EXECUTABLE = new File(System.getProperty("java.home"),
            "bin/java" + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""));
    private static final byte[] REMUXED = packets(8);

    @Test
    public void preparesValidatedOutputWithoutChangingSourceAndCloseDiscardsIt() throws Exception {
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            AtomicReference<File> cleaned = new AtomicReference<>();
            AtomicReference<File> output = new AtomicReference<>();
            try (FFmpegTsNormalizer.Prepared prepared = FFmpegTsNormalizer.prepare(EXECUTABLE, source,
                    file -> assertArrayEquals(REMUXED, Files.readAllBytes(file.toPath())), argv -> {
                        cleaned.set(new File(argv.get(argv.indexOf("-i") + 1)));
                        output.set(new File(argv.get(argv.size() - 1)));
                        assertEquals(source.getCanonicalFile().getParentFile(), cleaned.get().getParentFile());
                        assertEquals(source.getCanonicalFile().getParentFile(), output.get().getParentFile());
                        byte[] cleanedBytes = Files.readAllBytes(cleaned.get().toPath());
                        byte[] strippedBytes = strippedWrappedSegments();
                        assertOnlyContinuityNibblesDiffer(strippedBytes, cleanedBytes);
                        for (int packet = 0; packet < 12; packet++) {
                            assertEquals("The cleaned input must carry one continuous PID counter at packet " + packet,
                                    packet & 0x0f, packetCc(cleanedBytes, packet));
                        }
                        assertStrictRemuxCommand(argv);
                        Files.write(output.get().toPath(), REMUXED);
                        return new FakeProcess("ok", 0);
                    }, 1000, 8192)) {
                assertEquals(output.get(), prepared.getOutput());
                assertTrue(prepared.getOutput().isFile());
                assertFalse(cleaned.get().exists());
                assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            }
            assertFalse(output.get().exists());
            assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    @Test
    public void explicitCommitReplacesSourceOnlyAfterValidationAndDeletesItsBackup() throws Exception {
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            AtomicReference<List<String>> command = new AtomicReference<>();
            try (FFmpegTsNormalizer.Prepared prepared = FFmpegTsNormalizer.prepare(EXECUTABLE, source,
                    file -> {
                        assertArrayEquals(original, Files.readAllBytes(source.toPath()));
                        assertArrayEquals(REMUXED, Files.readAllBytes(file.toPath()));
                    }, argv -> {
                        command.set(argv);
                        Files.write(new File(argv.get(argv.size() - 1)).toPath(), REMUXED);
                        return new FakeProcess("ok", 0);
                    }, 1000, 8192)) {
                prepared.commit();
                prepared.commit();
                assertArrayEquals(REMUXED, Files.readAllBytes(source.toPath()));
                assertFalse(prepared.getOutput().exists());
                assertEquals(1, source.getParentFile().list().length);
            }
            assertArrayEquals(REMUXED, Files.readAllBytes(source.toPath()));
            assertStrictRemuxCommand(command.get());
        });
    }

    @Test
    public void successfulExitWithUnfinishedPipeFailsWithoutTouchingOriginal() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeProcess process = new FakeProcess(new InputStream() {
            @Override public int read() throws IOException {
                try {
                    release.await();
                    return -1;
                } catch (InterruptedException error) {
                    throw new IOException(error);
                }
            }
        }, 0);
        try {
            withSource(source -> {
                byte[] original = Files.readAllBytes(source.toPath());
                long started = System.nanoTime();
                expectFailure(() -> FFmpegTsNormalizer.prepare(EXECUTABLE, source,
                        file -> { throw new AssertionError("An undrained child must not validate"); }, argv -> {
                            Files.write(new File(argv.get(argv.size() - 1)).toPath(), REMUXED);
                            return process;
                        }, 1000, 8192), "drain");
                assertTrue((System.nanoTime() - started) / 1_000_000 < 1500);
                assertTrue(process.destroyed);
                assertArrayEquals(original, Files.readAllBytes(source.toPath()));
                assertEquals(1, source.getParentFile().list().length);
            });
        } finally {
            release.countDown();
        }
    }

    @Test
    public void validatorFailureDiscardsAllTemporaryFilesAndKeepsOriginal() throws Exception {
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            expectFailure(() -> prepareOutput(source, file -> {
                throw new IOException("Missing required audio track");
            }, new FakeProcess("ok", 0)), "Missing required audio track");
            assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    @Test
    public void nonzeroExitNeverValidatesOrCommitsEvenIfOutputExists() throws Exception {
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            FakeProcess process = new FakeProcess(new String(new char[50_000]).replace('\0', 'x'), 1);
            IOException error = expectFailure(() -> FFmpegTsNormalizer.prepare(EXECUTABLE, source,
                    file -> { throw new AssertionError("A failed remux must not validate"); }, argv -> {
                        Files.write(new File(argv.get(argv.size() - 1)).toPath(), REMUXED);
                        return process;
                    }, 1000, 1024), "status 1");
            assertTrue(error.getMessage(), error.getMessage().contains("truncated at 1024 bytes"));
            assertTrue(error.getMessage().length() < 1600);
            assertEquals(0, process.input.available());
            assertTrue(process.destroyed);
            assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    @Test
    public void zeroExitWithEmptyOutputFailsBeforeValidation() throws Exception {
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            expectFailure(() -> FFmpegTsNormalizer.prepare(EXECUTABLE, source,
                    file -> { throw new AssertionError("Empty output must not validate"); },
                    argv -> new FakeProcess("ok", 0), 1000, 8192), "empty");
            assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    @Test
    public void launchFailureAndPlainTsInputKeepOriginalAndLeaveNoTemporaryFiles() throws Exception {
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            expectFailure(() -> FFmpegTsNormalizer.prepare(EXECUTABLE, source, file -> {},
                    argv -> { throw new IOException("exec permission denied"); }, 1000, 8192), "exec permission denied");
            assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            assertEquals(1, source.getParentFile().list().length);
            Files.write(source.toPath(), packets(6));
            expectFailure(() -> FFmpegTsNormalizer.prepare(EXECUTABLE, source, file -> {},
                    argv -> { throw new AssertionError("Plain TS must not invoke the PNG repair"); }, 1000, 8192), "PNG");
            assertArrayEquals(packets(6), Files.readAllBytes(source.toPath()));
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    @Test
    public void timeoutDestroysChildAndPreservesOriginal() throws Exception {
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            FakeProcess process = new FakeProcess("partial remux", null);
            long started = System.nanoTime();
            expectFailure(() -> FFmpegTsNormalizer.prepare(EXECUTABLE, source, file -> {}, argv -> {
                Files.write(new File(argv.get(argv.size() - 1)).toPath(), REMUXED);
                return process;
            }, 75, 8192), "timed out after 75 ms");
            assertTrue(process.destroyed);
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1500);
            assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    @Test
    public void continuouslyFloodingChildStillTimesOutWithBoundedLog() throws Exception {
        AtomicReference<FakeProcess> child = new AtomicReference<>();
        InputStream flood = new InputStream() {
            @Override public int read() { return child.get().destroyed ? -1 : 'x'; }
            @Override public int read(byte[] bytes, int offset, int count) {
                if (child.get().destroyed) return -1;
                Arrays.fill(bytes, offset, offset + count, (byte) 'x');
                return count;
            }
        };
        FakeProcess process = new FakeProcess(flood, null);
        child.set(process);
        withSource(source -> {
            long started = System.nanoTime();
            IOException error = expectFailure(() -> FFmpegTsNormalizer.prepare(EXECUTABLE, source,
                    file -> {}, argv -> process, 75, 1024), "timed out");
            assertTrue(error.getMessage(), error.getMessage().contains("truncated at 1024 bytes"));
            assertTrue(error.getMessage().length() < 1600);
            assertTrue(process.destroyed);
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1500);
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    @Test
    public void interruptionStopsChildCleansOutputsAndPreservesInterruptFlag() throws Exception {
        FakeProcess process = new FakeProcess("partial remux", null);
        try {
            withSource(source -> {
                byte[] original = Files.readAllBytes(source.toPath());
                expectFailure(() -> FFmpegTsNormalizer.prepare(EXECUTABLE, source,
                        file -> { throw new AssertionError("A cancelled child must not validate"); }, argv -> {
                            Files.write(new File(argv.get(argv.size() - 1)).toPath(), REMUXED);
                            Thread.currentThread().interrupt();
                            return process;
                        }, 1000, 8192), "interrupted");
                assertTrue(Thread.currentThread().isInterrupted());
                assertTrue(process.destroyed);
                assertArrayEquals(original, Files.readAllBytes(source.toPath()));
                assertEquals(1, source.getParentFile().list().length);
            });
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void alreadyInterruptedCallerCannotStartNormalization() throws Exception {
        try {
            Thread.currentThread().interrupt();
            expectFailure(() -> FFmpegTsNormalizer.prepare(EXECUTABLE, null, file -> {}, argv -> {
                throw new AssertionError("Already interrupted caller must not launch");
            }, 1000, 8192), "interrupted");
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void sourceLengthOrTimestampChangesPreventCommit() throws Exception {
        withSource(source -> {
            byte[] changed = {1, 2, 3};
            try (FFmpegTsNormalizer.Prepared prepared = prepareOutput(source, file -> {}, new FakeProcess("ok", 0))) {
                Files.write(source.toPath(), changed);
                expectFailure(prepared::commit, "Source TS changed");
                assertArrayEquals(changed, Files.readAllBytes(source.toPath()));
            }
            assertEquals(1, source.getParentFile().list().length);
        });
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            try (FFmpegTsNormalizer.Prepared prepared = prepareOutput(source, file -> {}, new FakeProcess("ok", 0))) {
                assertTrue(source.setLastModified(source.lastModified() + 10_000));
                expectFailure(prepared::commit, "Source TS changed");
                assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            }
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    @Test
    public void sourceChangedDuringPreparationIsNotReplacedAndOutputIsDiscarded() throws Exception {
        withSource(source -> {
            byte[] changed = {9, 8, 7};
            expectFailure(() -> prepareOutput(source, file -> Files.write(source.toPath(), changed),
                    new FakeProcess("ok", 0)), "Source TS changed");
            assertArrayEquals(changed, Files.readAllBytes(source.toPath()));
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    @Test
    public void changedValidatedOutputOrClosedPreparationCannotCommit() throws Exception {
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            try (FFmpegTsNormalizer.Prepared prepared = prepareOutput(source, file -> {}, new FakeProcess("ok", 0))) {
                Files.write(prepared.getOutput().toPath(), new byte[]{1, 2, 3});
                expectFailure(prepared::commit, "Validated normalized TS changed");
            }
            FFmpegTsNormalizer.Prepared closed = prepareOutput(source, file -> {}, new FakeProcess("ok", 0));
            closed.close();
            closed.close();
            expectFailure(closed::commit, "already been closed");
            assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    @Test
    public void installRenameFailureRestoresOriginalFromBackup() throws Exception {
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            try (FFmpegTsNormalizer.Prepared prepared = prepareOutput(source, file -> {}, new FakeProcess("ok", 0))) {
                expectFailure(() -> prepared.commit((from, to) -> {
                    if (from.equals(prepared.getOutput())) return false;
                    return from.renameTo(to);
                }), "original was restored");
                assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            }
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    @Test
    public void rollbackFailureKeepsRecoverableBackupAndCloseNeverDeletesIt() throws Exception {
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            AtomicReference<File> backup = new AtomicReference<>();
            try (FFmpegTsNormalizer.Prepared prepared = prepareOutput(source, file -> {}, new FakeProcess("ok", 0))) {
                IOException error = expectFailure(() -> prepared.commit((from, to) -> {
                    if (from.equals(source)) {
                        backup.set(to);
                        return from.renameTo(to);
                    }
                    return false;
                }), "original is preserved at");
                assertTrue(error.getMessage(), error.getMessage().contains(backup.get().getAbsolutePath()));
                assertArrayEquals(original, Files.readAllBytes(backup.get().toPath()));
                assertFalse(source.exists());
            }
            assertArrayEquals(original, Files.readAllBytes(backup.get().toPath()));
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    @Test
    public void successfulExitWithPipeReadFailureIsNotAccepted() throws Exception {
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            FakeProcess process = new FakeProcess(new InputStream() {
                @Override public int read() throws IOException { throw new IOException("broken remux pipe"); }
            }, 0);
            IOException error = expectFailure(() -> prepareOutput(source, file -> {
                throw new AssertionError("Capture failure must not validate");
            }, process), "output capture failed");
            assertTrue(error.getMessage(), error.getMessage().contains("broken remux pipe"));
            assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    @Test
    public void interruptedCommitCannotMoveTheOriginal() throws Exception {
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            try (FFmpegTsNormalizer.Prepared prepared = prepareOutput(source, file -> {}, new FakeProcess("ok", 0))) {
                try {
                    Thread.currentThread().interrupt();
                    expectFailure(prepared::commit, "interrupted");
                    assertTrue(Thread.currentThread().isInterrupted());
                    assertArrayEquals(original, Files.readAllBytes(source.toPath()));
                } finally {
                    Thread.interrupted();
                }
            }
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    @Test
    public void symlinkOutputCannotAliasOrReplaceOriginal() throws Exception {
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            requireSymlinkSupport(source);
            expectFailure(() -> FFmpegTsNormalizer.prepare(EXECUTABLE, source,
                    file -> { throw new AssertionError("Symlink output must not validate"); }, argv -> {
                        Path output = new File(argv.get(argv.size() - 1)).toPath();
                        Files.delete(output);
                        Files.createSymbolicLink(output, source.toPath());
                        return new FakeProcess("ok", 0);
                    }, 1000, 8192), "private regular file");
            assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    @Test
    public void symlinkSourceIsRejectedBeforeCleaningOrLaunching() throws Exception {
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            requireSymlinkSupport(source);
            Path link = source.getParentFile().toPath().resolve("link.ts");
            Files.createSymbolicLink(link, source.toPath());
            try {
                expectFailure(() -> FFmpegTsNormalizer.prepare(EXECUTABLE, link.toFile(), file -> {}, argv -> {
                    throw new AssertionError("Symlink source must not launch");
                }, 1000, 8192), "without symlinks");
            } finally {
                Files.deleteIfExists(link);
            }
            assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    private static void requireSymlinkSupport(File source) throws IOException {
        Path probe = source.getParentFile().toPath().resolve("symlink-support-probe");
        try {
            Files.createSymbolicLink(probe, source.toPath());
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            org.junit.Assume.assumeNoException("Host must support file symlinks", error);
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    @Test
    public void realJvmChildCanFloodStderrAndPrepareValidatedOutputWithoutDeadlock() throws Exception {
        File compiledTests = new File(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            try (FFmpegTsNormalizer.Prepared prepared = FFmpegTsNormalizer.prepare(EXECUTABLE, source,
                    file -> assertArrayEquals(REMUXED, Files.readAllBytes(file.toPath())),
                    argv -> new ProcessBuilder(EXECUTABLE.getAbsolutePath(), "-cp", compiledTests.getAbsolutePath(),
                            Child.class.getName(), argv.get(argv.size() - 1), "flood")
                            .redirectErrorStream(true).start(), 5000, 1024)) {
                assertArrayEquals(original, Files.readAllBytes(source.toPath()));
                prepared.commit();
                assertArrayEquals(REMUXED, Files.readAllBytes(source.toPath()));
            }
            assertEquals(1, source.getParentFile().list().length);
        });
    }

    @Test
    public void realJvmChildTimeoutLeavesOriginalIntactAndCleansOutputs() throws Exception {
        File compiledTests = new File(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        AtomicReference<Process> child = new AtomicReference<>();
        withSource(source -> {
            byte[] original = Files.readAllBytes(source.toPath());
            long started = System.nanoTime();
            expectFailure(() -> FFmpegTsNormalizer.prepare(EXECUTABLE, source, file -> {
                throw new AssertionError("Timed out child must not validate");
            }, argv -> {
                Process process = new ProcessBuilder(EXECUTABLE.getAbsolutePath(), "-cp", compiledTests.getAbsolutePath(),
                        Child.class.getName(), argv.get(argv.size() - 1), "hang").redirectErrorStream(true).start();
                child.set(process);
                return process;
            }, 150, 8192), "timed out");
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3000);
            assertArrayEquals(original, Files.readAllBytes(source.toPath()));
            assertEquals(1, source.getParentFile().list().length);
            for (int attempt = 0; attempt < 40; attempt++) {
                try {
                    child.get().exitValue();
                    return;
                } catch (IllegalThreadStateException running) {
                    Thread.sleep(25);
                }
            }
            throw new AssertionError("Timed out JVM child was not terminated");
        });
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            if ("flood".equals(args[1])) {
                byte[] log = new byte[8192];
                Arrays.fill(log, (byte) 'x');
                for (int index = 0; index < 32; index++) System.err.write(log, 0, log.length);
            }
            Files.write(new File(args[0]).toPath(), packets(8));
            if ("hang".equals(args[1])) Thread.sleep(60_000);
        }
    }

    private static FFmpegTsNormalizer.Prepared prepareOutput(File source, FFmpegTsNormalizer.OutputValidator validator,
                                                             FakeProcess process) throws IOException {
        return FFmpegTsNormalizer.prepare(EXECUTABLE, source, validator, argv -> {
            Files.write(new File(argv.get(argv.size() - 1)).toPath(), REMUXED);
            return process;
        }, 1000, 8192);
    }

    private static IOException expectFailure(IoAction action, String expected) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected IOException containing: " + expected);
        } catch (IOException error) {
            assertTrue(error.toString(), error.getMessage().contains(expected));
            return error;
        }
    }

    private interface IoAction {
        void run() throws Exception;
    }

    private static byte[] packets(int count) {
        byte[] bytes = new byte[count * 188];
        Arrays.fill(bytes, (byte) 0x55);
        for (int packet = 0; packet < count; packet++) {
            int offset = packet * 188;
            bytes[offset] = 0x47;
            bytes[offset + 1] = 0x40;
            bytes[offset + 2] = 0;
            bytes[offset + 3] = 0x10;
        }
        return bytes;
    }

    private static byte[] independentSegmentPackets(int count) {
        byte[] bytes = new byte[count * 188];
        for (int packet = 0; packet < count; packet++) {
            int offset = packet * 188;
            Arrays.fill(bytes, offset, offset + 188, (byte) (0x50 + packet));
            bytes[offset] = 0x47;
            bytes[offset + 1] = 0x40;
            bytes[offset + 2] = 0;
            bytes[offset + 3] = (byte) (0x10 | (packet & 0x0f));
        }
        return bytes;
    }

    private static byte[] strippedWrappedSegments() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(independentSegmentPackets(6));
        output.write(independentSegmentPackets(6));
        return output.toByteArray();
    }

    private static int packetCc(byte[] media, int packet) {
        return media[packet * 188 + 3] & 0x0f;
    }

    private static void assertOnlyContinuityNibblesDiffer(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            if (index % 188 == 3) {
                assertEquals("Only the continuity counter nibble may change at byte " + index,
                        expected[index] & 0xf0, actual[index] & 0xf0);
            } else {
                assertEquals("Unexpected cleaned input change at byte " + index,
                        expected[index] & 0xff, actual[index] & 0xff);
            }
        }
    }

    private static void assertStrictRemuxCommand(List<String> argv) {
        assertTrue(argv.contains("-xerror"));
        assertEquals("file", argv.get(argv.indexOf("-protocol_whitelist") + 1));
        assertEquals("mpegts", argv.get(argv.indexOf("-format_whitelist") + 1));
        assertEquals("mpegts", argv.get(argv.indexOf("-f") + 1));
        assertEquals("mpegts", argv.get(argv.lastIndexOf("-f") + 1));
        assertTrue(argv.contains("0:v:0"));
        assertTrue(argv.contains("0:a:0"));
        assertFalse(argv.contains("0:a:0?"));
        assertEquals("copy", argv.get(argv.indexOf("-c") + 1));
    }

    private static byte[] wrapped() throws IOException {
        byte[] prefix = new byte[126];
        Arrays.fill(prefix, (byte) 0x50);
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, prefix, 0, signature.length);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(prefix);
        output.write(independentSegmentPackets(6));
        output.write(prefix);
        output.write(independentSegmentPackets(6));
        return output.toByteArray();
    }

    private static void withSource(SourceAssertion assertion) throws Exception {
        Path directory = Files.createTempDirectory("ffmpeg-ts-normalizer-test");
        File source = directory.resolve("proxy.ts").toFile();
        try {
            Files.write(source.toPath(), wrapped());
            assertion.check(source);
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private interface SourceAssertion {
        void check(File source) throws Exception;
    }

    private static final class FakeProcess extends Process {
        private final InputStream input;
        private final Integer exit;
        private volatile boolean destroyed;

        private FakeProcess(String log, Integer exit) {
            this(new ByteArrayInputStream(log.getBytes(java.nio.charset.StandardCharsets.UTF_8)), exit);
        }

        private FakeProcess(InputStream input, Integer exit) {
            this.input = input;
            this.exit = exit;
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { throw new AssertionError("Unbounded waitFor is forbidden"); }
        @Override public int exitValue() {
            if (exit == null && !destroyed) throw new IllegalThreadStateException("running");
            return exit == null ? 143 : exit;
        }
        @Override public void destroy() { destroyed = true; }
    }
}
