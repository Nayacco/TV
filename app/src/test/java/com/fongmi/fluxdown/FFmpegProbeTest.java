package com.fongmi.fluxdown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class FFmpegProbeTest {
    private static final File EXECUTABLE = new File(System.getProperty("java.home"),
            "bin/java" + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""));

    @Test
    public void reportsInputAndStreamMetadataWithoutTreatingExitOneAsFailure() throws Exception {
        FakeProcess process = new FakeProcess("Input #0, mov, from 'video.mp4':\n"
                + "  Stream #0:0: Video: h264\n  Stream #0:1: Audio: aac\n"
                + "At least one output file must be specified\n", 1);
        AtomicReference<List<String>> command = new AtomicReference<>();
        withMedia(media -> {
            byte[] before = Files.readAllBytes(media.toPath());
            String result = FFmpegProbe.inspect(EXECUTABLE, media, argv -> {
                command.set(argv);
                return process;
            }, 100, 8192);
            assertTrue(result, result.contains("FFmpeg probe: exited 1"));
            assertTrue(result, result.contains("Exit 1 is expected"));
            assertTrue(result, result.contains("Video: h264"));
            assertTrue(result, result.contains("Audio: aac"));
            assertTrue(process.destroyed);
            assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(media.toPath())));
            List<String> argv = command.get();
            assertNotNull(argv);
            assertEquals(media.getAbsolutePath(), argv.get(argv.size() - 1));
            assertEquals("-i", argv.get(argv.size() - 2));
            assertEquals("file", argv.get(argv.indexOf("-protocol_whitelist") + 1));
            String formats = argv.get(argv.indexOf("-format_whitelist") + 1);
            assertTrue(formats.contains("mov"));
            assertTrue(formats.contains("mpegts"));
            assertFalse(formats.contains("hls"));
            assertFalse(formats.contains("dash"));
            assertFalse(formats.contains("concat"));
            assertFalse(argv.contains("-y"));
            assertFalse(argv.contains("-c"));
            assertFalse(argv.contains("-f"));
        });
    }

    @Test
    public void preservesMalformedInputDiagnostics() throws Exception {
        withMedia(media -> {
            String result = FFmpegProbe.inspect(EXECUTABLE, media,
                    argv -> new FakeProcess("Invalid data found when processing input\n", 1), 100, 8192);
            assertTrue(result, result.contains("Invalid data found"));
            assertTrue(result, result.contains("exit code alone does not establish"));
        });
    }

    @Test
    public void doesNotLaunchMissingOrUnreadableInputs() throws Exception {
        FFmpegProbe.ProcessLauncher forbidden = argv -> {
            throw new AssertionError("An unavailable probe must not start a child");
        };
        withMedia(media -> {
            assertTrue(FFmpegProbe.inspect(null, media, forbidden, 100, 8192).contains("executable is missing"));
            assertTrue(FFmpegProbe.inspect(EXECUTABLE, null, forbidden, 100, 8192).contains("file is missing"));
            assertTrue(FFmpegProbe.inspect(EXECUTABLE, media.getParentFile(), forbidden, 100, 8192).contains("file is missing"));
            assertTrue(FFmpegProbe.inspect(EXECUTABLE, media, forbidden, 0, 8192).contains("invalid diagnostic limits"));
        });
    }

    @Test
    public void reportsLaunchFailureWithoutThrowing() throws Exception {
        withMedia(media -> {
            String result = FFmpegProbe.inspect(EXECUTABLE, media,
                    argv -> { throw new IOException("Permission denied"); }, 100, 8192);
            assertTrue(result, result.contains("could not start (IOException: Permission denied)"));
        });
    }

    @Test
    public void boundsOutputWhileStillDrainingTheChildPipe() throws Exception {
        FakeProcess process = new FakeProcess(new String(new char[50_000]).replace('\0', 'x'), 1);
        withMedia(media -> {
            String result = FFmpegProbe.inspect(EXECUTABLE, media, argv -> process, 100, 1024);
            assertTrue(result, result.contains("truncated at 1024 bytes"));
            assertTrue(result.length() < 1800);
            assertEquals(0, process.input.available());
        });
    }

    @Test
    public void killsProcessAfterBoundedTimeout() throws Exception {
        FakeProcess process = new FakeProcess("partial input metadata", null);
        withMedia(media -> {
            long start = System.nanoTime();
            String result = FFmpegProbe.inspect(EXECUTABLE, media, argv -> process, 75, 8192);
            assertTrue(result, result.contains("timed out after 75 ms"));
            assertTrue(process.destroyed);
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1500);
        });
    }

    @Test
    public void continuousOutputCannotOverrunMemoryOrPreventTimeout() throws Exception {
        AtomicReference<FakeProcess> child = new AtomicReference<>();
        InputStream flood = new InputStream() {
            @Override
            public int read() {
                return child.get().destroyed ? -1 : 'x';
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (child.get().destroyed) return -1;
                java.util.Arrays.fill(buffer, offset, offset + length, (byte) 'x');
                return length;
            }
        };
        FakeProcess process = new FakeProcess(flood, null);
        child.set(process);
        withMedia(media -> {
            long start = System.nanoTime();
            String result = FFmpegProbe.inspect(EXECUTABLE, media, argv -> process, 75, 1024);
            assertTrue(result, result.contains("timed out after 75 ms"));
            assertTrue(result, result.contains("truncated at 1024 bytes"));
            assertTrue(result.length() < 1800);
            assertTrue(process.destroyed);
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1500);
        });
    }

    @Test
    public void retainedPipeDoesNotBlockReturnAfterChildExit() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        InputStream blocked = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    release.await();
                    return -1;
                } catch (InterruptedException error) {
                    throw new IOException(error);
                }
            }
        };
        FakeProcess process = new FakeProcess(blocked, 1);
        try {
            withMedia(media -> {
                long start = System.nanoTime();
                String result = FFmpegProbe.inspect(EXECUTABLE, media, argv -> process, 100, 8192);
                assertTrue(result, result.contains("Output drain stopped waiting after 250 ms"));
                assertTrue((System.nanoTime() - start) / 1_000_000 < 1500);
            });
        } finally {
            release.countDown();
        }
    }

    @Test
    public void interruptionStopsProbeAndRestoresCallerFlag() throws Exception {
        FakeProcess process = new FakeProcess("partial metadata", null);
        try {
            withMedia(media -> {
                String result = FFmpegProbe.inspect(EXECUTABLE, media, argv -> {
                    Thread.currentThread().interrupt();
                    return process;
                }, 1000, 8192);
                assertTrue(result, result.startsWith("FFmpeg probe: interrupted"));
                assertTrue(Thread.currentThread().isInterrupted());
                assertTrue(process.destroyed);
            });
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void alreadyInterruptedCallerDoesNotLaunchProbe() throws Exception {
        try {
            Thread.currentThread().interrupt();
            String result = FFmpegProbe.inspect(EXECUTABLE, null, argv -> {
                throw new AssertionError("Interrupted caller must not launch FFmpeg");
            }, 1000, 8192);
            assertTrue(result.contains("interrupted (not started)"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void reportsReaderFailureWithoutLosingExitStatus() throws Exception {
        FakeProcess process = new FakeProcess(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("closed pipe");
            }
        }, 1);
        withMedia(media -> {
            String result = FFmpegProbe.inspect(EXECUTABLE, media, argv -> process, 100, 8192);
            assertTrue(result, result.contains("FFmpeg probe: exited 1"));
            assertTrue(result, result.contains("Output capture error: IOException: closed pipe"));
        });
    }

    @Test
    public void capturesMergedOutputFromAnActualJvmChild() throws Exception {
        // Gradle's worker isolates test classes from java.class.path. Use the actual test
        // code-source location; this child needs only its own nested fixture class.
        File compiledTests = new File(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String previousClasspath = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", "gradle-worker.jar");
            withMedia(media -> {
                String result = FFmpegProbe.inspect(EXECUTABLE, media,
                        argv -> new ProcessBuilder(EXECUTABLE.getAbsolutePath(), "-cp",
                                compiledTests.getAbsolutePath(), Child.class.getName())
                                .redirectErrorStream(true).start(), 5000, 8192);
                assertTrue(result, result.contains("FFmpeg probe: exited 1"));
                assertTrue(result, result.contains("Input #0, mov"));
                assertTrue(result, result.contains("Video: h264"));
                assertTrue(result, result.contains("Audio: aac"));
            });
        } finally {
            if (previousClasspath == null) System.clearProperty("java.class.path");
            else System.setProperty("java.class.path", previousClasspath);
        }
    }

    public static final class Child {
        public static void main(String[] args) {
            System.out.println("Input #0, mov");
            System.err.println("Stream #0:0: Video: h264");
            System.err.println("Stream #0:1: Audio: aac");
            if (args.length > 0 && "flood".equals(args[0])) {
                byte[] output = new byte[8192];
                java.util.Arrays.fill(output, (byte) 'x');
                for (int index = 0; index < 32; index++) System.err.write(output, 0, output.length);
            }
            System.exit(1);
        }
    }

    @Test
    public void drainsActualChildOutputBeyondTheCaptureLimit() throws Exception {
        File compiledTests = new File(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        withMedia(media -> {
            String result = FFmpegProbe.inspect(EXECUTABLE, media,
                    argv -> new ProcessBuilder(EXECUTABLE.getAbsolutePath(), "-cp",
                            compiledTests.getAbsolutePath(), Child.class.getName(), "flood")
                            .redirectErrorStream(true).start(), 5000, 1024);
            assertTrue(result, result.startsWith("FFmpeg probe: exited 1"));
            assertTrue(result, result.contains("truncated at 1024 bytes"));
            assertFalse(result, result.contains("timed out"));
            assertTrue(result.length() < 1800);
        });
    }

    private static void withMedia(MediaAssertion assertion) throws Exception {
        Path path = Files.createTempFile("ffmpeg-probe-test", ".mp4");
        try {
            Files.write(path, new byte[]{0, 1, 2, 3});
            assertion.check(path.toFile());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private interface MediaAssertion {
        void check(File media) throws Exception;
    }

    private static final class FakeProcess extends Process {
        private final InputStream input;
        private final Integer exit;
        private volatile boolean destroyed;

        private FakeProcess(String output, Integer exit) {
            this(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)), exit);
        }

        private FakeProcess(InputStream input, Integer exit) {
            this.input = input;
            this.exit = exit;
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            throw new AssertionError("Unbounded waitFor is forbidden");
        }

        @Override
        public int exitValue() {
            if (exit == null) throw new IllegalThreadStateException("running");
            return exit;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }
    }
}
