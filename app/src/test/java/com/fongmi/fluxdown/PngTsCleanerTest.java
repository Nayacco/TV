package com.fongmi.fluxdown;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;

public class PngTsCleanerTest {
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void removesEachPngPrefixWithoutChangingMediaPacketsOrSource() throws Exception {
        byte[] first = packets(5, 0x21);
        byte[] second = packets(7, 0x32);
        byte[] wrapped = join(prefix(126), first, prefix(241), second);
        File source = temporary.newFile("wrapped.ts");
        Files.write(source.toPath(), wrapped);
        File output = new File(temporary.getRoot(), "clean.ts");

        assertTrue(PngTsCleaner.hasPngPrefix(source));
        PngTsCleaner.clean(source, output);

        assertArrayEquals(join(first, second), Files.readAllBytes(output.toPath()));
        assertArrayEquals(wrapped, Files.readAllBytes(source.toPath()));
    }

    @Test
    public void rejectsInvalidTransportHeadersAtAnEstablishedPacketBoundary() throws Exception {
        for (int kind = 0; kind < 4; kind++) {
            byte[] bad = packets(1, 0x42);
            if (kind == 0) bad[1] |= (byte) 0x80; // Transport error indicator.
            if (kind == 1) bad[3] = 0; // Reserved adaptation field control.
            if (kind == 2) {
                bad[3] = 0x30; // Adaptation and payload require room for payload.
                bad[4] = (byte) 183;
            }
            if (kind == 3) {
                bad[3] = 0x20; // Adaptation-only must fill the packet.
                bad[4] = 0;
            }
            File source = temporary.newFile("invalid-" + kind + ".ts");
            byte[] wrapped = join(prefix(126), packets(5, 0x21), bad, packets(5, 0x32));
            Files.write(source.toPath(), wrapped);
            try {
                PngTsCleaner.clean(source, new File(temporary.getRoot(), "invalid-out-" + kind + ".ts"));
                fail("An invalid transport packet must not be copied or silently skipped");
            } catch (IOException expected) {
                assertArrayEquals(wrapped, Files.readAllBytes(source.toPath()));
            }
        }
    }

    @Test
    public void removesOnlyItsPartialOutputWhenTheFinalPacketIsTruncated() throws Exception {
        byte[] wrapped = join(prefix(126), packets(5, 0x21), new byte[] {0x47, 0x01, 0x00, 0x10});
        File source = temporary.newFile("truncated.ts");
        Files.write(source.toPath(), wrapped);
        File output = new File(temporary.getRoot(), "partial.ts");
        try {
            PngTsCleaner.clean(source, output);
            fail("Truncated packet must fail the entire cleaning operation");
        } catch (IOException expected) {
            assertFalse("A failed operation must not leave a usable-looking partial output", output.exists());
            assertArrayEquals(wrapped, Files.readAllBytes(source.toPath()));
        }
    }

    @Test
    public void neverOverwritesAnExistingOutputEvenIfEmpty() throws Exception {
        File source = temporary.newFile("protected-source.ts");
        byte[] wrapped = join(prefix(126), packets(5, 0x21));
        Files.write(source.toPath(), wrapped);
        for (int size : new int[] {0, 3}) {
            File output = temporary.newFile("protected-output-" + size + ".ts");
            byte[] existing = new byte[size];
            java.util.Arrays.fill(existing, (byte) 0x7a);
            Files.write(output.toPath(), existing);
            try {
                PngTsCleaner.clean(source, output);
                fail("Existing outputs must not be overwritten");
            } catch (IOException expected) {
                assertTrue(output.exists());
                assertArrayEquals(existing, Files.readAllBytes(output.toPath()));
                assertArrayEquals(wrapped, Files.readAllBytes(source.toPath()));
            }
        }
    }

    @Test
    public void interruptionStopsDetectionAndCleaningWithoutClearingTheFlag() throws Exception {
        File source = temporary.newFile("interrupted.ts");
        byte[] wrapped = join(prefix(126), packets(5, 0x21));
        Files.write(source.toPath(), wrapped);
        File output = new File(temporary.getRoot(), "interrupted-output.ts");
        Thread.currentThread().interrupt();
        try {
            try {
                PngTsCleaner.hasPngPrefix(source);
                fail("Detection must honor interruption");
            } catch (InterruptedIOException expected) {
                assertTrue(Thread.currentThread().isInterrupted());
            }
            try {
                PngTsCleaner.clean(source, output);
                fail("Cleaning must honor interruption");
            } catch (InterruptedIOException expected) {
                assertTrue(Thread.currentThread().isInterrupted());
                assertFalse(output.exists());
                assertArrayEquals(wrapped, Files.readAllBytes(source.toPath()));
            }
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void preservesPngSignaturesInsideValidMediaPayloads() throws Exception {
        byte[] media = packets(6, 0x21);
        System.arraycopy(PNG, 0, media, 4, PNG.length);
        System.arraycopy(PNG, 0, media, 3 * 188 + 180, PNG.length);
        File source = writeSource("payload-png.ts", join(prefix(126), media));
        File output = new File(temporary.getRoot(), "payload-clean.ts");
        PngTsCleaner.clean(source, output);
        assertArrayEquals(media, Files.readAllBytes(output.toPath()));
    }

    @Test
    public void ignoresIsolatedSyncBytesAndFiveInvalidHeadersInsideThePrefix() throws Exception {
        byte[] pngPrefix = prefix(2000);
        pngPrefix[20] = 0x47;
        for (int index = 0; index < 5; index++) {
            int offset = 64 + 188 * index;
            pngPrefix[offset] = 0x47;
            pngPrefix[offset + 1] = 0;
            pngPrefix[offset + 3] = 0; // Reserved adaptation control, not a TS packet.
        }
        byte[] media = packets(7, 0x32);
        File source = writeSource("false-sync.ts", join(pngPrefix, media));
        File output = new File(temporary.getRoot(), "false-sync-clean.ts");
        PngTsCleaner.clean(source, output);
        assertArrayEquals(media, Files.readAllBytes(output.toPath()));
    }

    @Test
    public void permitsAdaptationOnlyAndAdaptationWithPayloadPackets() throws Exception {
        byte[] media = packets(7, 0x21);
        media[3] = 0x20;
        media[4] = (byte) 183;
        media[5] = 0;
        media[188 + 3] = 0x30;
        media[188 + 4] = 0;
        media[376 + 3] = 0x30;
        media[376 + 4] = (byte) 182;
        media[376 + 5] = 0;
        File source = writeSource("adaptation.ts", join(prefix(126), media));
        File output = new File(temporary.getRoot(), "adaptation-clean.ts");
        PngTsCleaner.clean(source, output);
        assertArrayEquals(media, Files.readAllBytes(output.toPath()));
    }

    @Test
    public void acceptsAPrefixAtTheScanLimitButRejectsALongerPrefix() throws Exception {
        byte[] media = packets(5, 0x21);
        File atLimit = writeSource("at-limit.ts", join(prefix(64 * 1024), media));
        File output = new File(temporary.getRoot(), "at-limit-clean.ts");
        PngTsCleaner.clean(atLimit, output);
        assertArrayEquals(media, Files.readAllBytes(output.toPath()));
        assertRejected("past-limit", join(prefix(64 * 1024 + 1), media));
    }

    @Test
    public void requiresFiveCompletePacketsAfterEveryPngPrefix() throws Exception {
        assertRejected("first-four", join(prefix(126), packets(4, 0x21)));
        assertRejected("first-four-before-next", join(prefix(126), packets(4, 0x21), prefix(81), packets(5, 0x32)));
        assertRejected("second-four", join(prefix(126), packets(5, 0x21), prefix(81), packets(4, 0x32)));
        assertRejected("trailing-png", join(prefix(126), packets(5, 0x21), PNG));
    }

    @Test
    public void neverResynchronizesAcrossUnrecognizedGarbage() throws Exception {
        assertRejected("unknown-gap", join(prefix(126), packets(5, 0x21), new byte[] {0x55}, packets(6, 0x32)));
        assertRejected("shifted-png", join(prefix(126), packets(5, 0x21), new byte[] {0}, prefix(126), packets(6, 0x32)));
    }

    @Test
    public void onlyDetectsTheLeadingSignatureAndNeverChangesOrdinaryTsOrMp4() throws Exception {
        for (byte[] bytes : new byte[][] {new byte[0], new byte[] {(byte) 0x89}, packets(5, 0x21),
                new byte[] {0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}}) {
            String name = "ordinary-" + bytes.length;
            File source = writeSource(name + ".bin", bytes);
            assertFalse(PngTsCleaner.hasPngPrefix(source));
            File output = new File(temporary.getRoot(), name + "-clean.ts");
            try {
                PngTsCleaner.clean(source, output);
                fail("Unwrapped inputs must not be rewritten");
            } catch (IOException expected) {
                assertFalse(output.exists());
                assertArrayEquals(bytes, Files.readAllBytes(source.toPath()));
            }
        }
        File signatureOnly = writeSource("signature-only.png", PNG);
        assertTrue(PngTsCleaner.hasPngPrefix(signatureOnly));
    }

    @Test
    public void protectsTheSourceWhenOutputNamesTheSameCanonicalFile() throws Exception {
        byte[] wrapped = join(prefix(126), packets(5, 0x21));
        File source = writeSource("same-file.ts", wrapped);
        File alias = new File(source.getParentFile(), "." + File.separator + source.getName());
        try {
            PngTsCleaner.clean(source, alias);
            fail("Source and output must be distinct files");
        } catch (IOException expected) {
            assertArrayEquals(wrapped, Files.readAllBytes(source.toPath()));
        }
    }

    @Test
    public void doesNotModifySourceWhenOutputCannotBeCreated() throws Exception {
        byte[] wrapped = join(prefix(126), packets(5, 0x21));
        File source = writeSource("bad-output-source.ts", wrapped);
        File directory = temporary.newFolder("existing-directory");
        File marker = new File(directory, "marker");
        Files.write(marker.toPath(), new byte[] {1, 2, 3});
        for (File output : new File[] {directory, new File(temporary.getRoot(), "missing-parent/out.ts"), null}) {
            try {
                PngTsCleaner.clean(source, output);
                fail("Invalid output paths must fail");
            } catch (IOException expected) {
                assertArrayEquals(wrapped, Files.readAllBytes(source.toPath()));
                assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(marker.toPath()));
            }
        }
    }

    @Test
    public void rejectsMissingAndDirectorySourcesWithoutCreatingOutput() throws Exception {
        int number = 0;
        for (File source : new File[] {null, new File(temporary.getRoot(), "missing.ts"), temporary.getRoot()}) {
            File output = new File(temporary.getRoot(), "invalid-source-" + number++ + ".ts");
            try {
                PngTsCleaner.clean(source, output);
                fail("Source must be a readable file");
            } catch (IOException expected) {
                assertFalse(output.exists());
            }
            try {
                PngTsCleaner.hasPngPrefix(source);
                fail("Prefix detection must reject unavailable sources");
            } catch (IOException expected) {
                assertFalse(output.exists());
            }
        }
    }

    @Test
    public void handlesShortReadsAndSignaturesAcrossBufferBoundaries() throws Exception {
        byte[] first = packets(348, 0x21);
        byte[] second = packets(7, 0x32);
        byte[] third = packets(5, 0x43);
        // The second PNG starts at 65531, so its signature spans a 64 KiB boundary.
        byte[] wrapped = join(prefix(107), first, prefix(513), second, prefix(64 * 1024), third);
        byte[] expected = join(first, second, third);
        for (int chunk : new int[] {1, 2, 7, 137, 188, 4093}) {
            ByteArrayInputStream input = new ByteArrayInputStream(wrapped) {
                @Override
                public synchronized int read(byte[] bytes, int offset, int length) {
                    return super.read(bytes, offset, Math.min(length, chunk));
                }
            };
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            PngTsCleaner.clean(input, output);
            assertArrayEquals("Short reads must not change the result: " + chunk, expected, output.toByteArray());
        }
    }

    @Test
    public void interruptionDuringReadingStopsBeforeWritingMedia() throws Exception {
        byte[] wrapped = join(prefix(126), packets(5, 0x21));
        ByteArrayInputStream input = new ByteArrayInputStream(wrapped) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                int count = super.read(bytes, offset, Math.min(length, 7));
                Thread.currentThread().interrupt();
                return count;
            }
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            PngTsCleaner.clean(input, output);
            fail("Cancellation during prefix reading must stop the operation");
        } catch (InterruptedIOException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
            assertArrayEquals(new byte[0], output.toByteArray());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void interruptionBetweenPacketsStopsWithoutWritingAdditionalPackets() throws Exception {
        byte[] media = packets(7, 0x21);
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override
            public synchronized void write(byte[] bytes, int offset, int length) {
                super.write(bytes, offset, length);
                Thread.currentThread().interrupt();
            }
        };
        try {
            PngTsCleaner.clean(new ByteArrayInputStream(join(prefix(126), media)), output);
            fail("Cancellation between packets must stop the operation");
        } catch (InterruptedIOException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
            assertArrayEquals(java.util.Arrays.copyOf(media, 188), output.toByteArray());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void propagatesOutputWriteFailureInsteadOfReturningSuccess() throws Exception {
        byte[] wrapped = join(prefix(126), packets(5, 0x21));
        OutputStream output = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("disk-full-fixture");
            }
        };
        try {
            PngTsCleaner.clean(new ByteArrayInputStream(wrapped), output);
            fail("Output write errors must fail the cleaning operation");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("disk-full-fixture"));
        }
    }

    private File writeSource(String name, byte[] bytes) throws Exception {
        File source = temporary.newFile(name);
        Files.write(source.toPath(), bytes);
        return source;
    }

    private void assertRejected(String name, byte[] wrapped) throws Exception {
        File source = writeSource(name + ".ts", wrapped);
        File output = new File(temporary.getRoot(), name + "-clean.ts");
        try {
            PngTsCleaner.clean(source, output);
            fail("Malformed wrapped input must fail: " + name);
        } catch (IOException expected) {
            assertFalse(output.exists());
            assertArrayEquals(wrapped, Files.readAllBytes(source.toPath()));
        }
    }

    private static byte[] packets(int count, int payload) {
        byte[] bytes = new byte[count * 188];
        java.util.Arrays.fill(bytes, (byte) payload);
        for (int index = 0; index < count; index++) {
            int offset = index * 188;
            bytes[offset] = 0x47;
            bytes[offset + 1] = 0x01;
            bytes[offset + 2] = 0x00;
            bytes[offset + 3] = (byte) (0x10 | (index & 15));
        }
        return bytes;
    }

    private static byte[] prefix(int length) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) 0x55);
        System.arraycopy(PNG, 0, bytes, 0, PNG.length);
        return bytes;
    }

    private static byte[] join(byte[]... pieces) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] piece : pieces) output.write(piece);
        return output.toByteArray();
    }
}
