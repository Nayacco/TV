package com.fongmi.fluxdown;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;

/** Removes bounded PNG wrappers only at positively identified 188-byte TS segment boundaries. */
public final class PngTsCleaner {
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final int PACKET_BYTES = 188;
    private static final int CONFIRM_PACKETS = 5;
    private static final int MAX_PREFIX_BYTES = 64 * 1024;
    private static final int LOOKAHEAD_BYTES = MAX_PREFIX_BYTES + CONFIRM_PACKETS * PACKET_BYTES;

    private PngTsCleaner() {
    }

    /** Checks only the leading signature; a true result does not prove that the file contains TS. */
    public static boolean hasPngPrefix(File source) throws IOException {
        checkInterrupted();
        if (source == null || !source.isFile() || !source.canRead()) {
            throw new IOException("Source is not a readable file");
        }
        try (FileInputStream input = new FileInputStream(source)) {
            for (byte expected : PNG) {
                checkInterrupted();
                if (input.read() != (expected & 0xff)) return false;
            }
            checkInterrupted();
            return true;
        }
    }

    /**
     * Creates a new cleaned file without changing the source. Existing outputs, including empty
     * files, are rejected. A failed/interrupted operation removes only the output it created.
     * This validates packet structure, not media decodability or episode completeness.
     */
    public static void clean(File source, File output) throws IOException {
        cleanFile(source, output, false);
    }

    static void cleanForRemux(File source, File output) throws IOException {
        cleanFile(source, output, true);
    }

    private static void cleanFile(File source, File output, boolean stitchContinuity) throws IOException {
        checkInterrupted();
        if (source == null || !source.isFile() || !source.canRead()) {
            throw new IOException("Source is not a readable file");
        }
        if (output == null || source.getCanonicalFile().equals(output.getCanonicalFile())) {
            throw new IOException("Output must be a different file from the source");
        }
        if (!output.createNewFile()) throw new IOException("Output already exists");
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream fileOutput = new FileOutputStream(output);
             BufferedOutputStream destination = new BufferedOutputStream(fileOutput, 64 * 1024)) {
            clean(input, destination, stitchContinuity);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                if (!output.delete() && output.exists()) {
                    failure.addSuppressed(new IOException("Unable to remove incomplete TS output"));
                }
            } catch (SecurityException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    // The same pipeline is exercised with short-read/cancelled I/O in JVM tests. Callers own
    // the streams; file lifecycle and removal of partial outputs belong to the File overload.
    static void clean(InputStream source, OutputStream output) throws IOException {
        clean(source, output, false);
    }

    static void cleanForRemux(InputStream source, OutputStream output) throws IOException {
        clean(source, output, true);
    }

    private static void clean(InputStream source, OutputStream output,
                              boolean stitchContinuity) throws IOException {
        checkInterrupted();
        PushbackInputStream input = new PushbackInputStream(
                new BufferedInputStream(source, 64 * 1024), LOOKAHEAD_BYTES);
        byte[] lookahead = new byte[LOOKAHEAD_BYTES];
        byte[] packet = new byte[PACKET_BYTES];
        ContinuityState continuity = stitchContinuity ? new ContinuityState() : null;
        stripPrefix(input, lookahead);
        if (continuity != null) continuity.beginSegment();
        while (true) {
            int count = readFully(input, packet);
            checkInterrupted();
            if (count == 0) return;
            if (count != PACKET_BYTES) throw new IOException("Truncated TS packet");
            if (validPacket(packet, 0)) {
                if (continuity != null) continuity.rewrite(packet);
                output.write(packet);
            } else if (pngSignature(packet)) {
                input.unread(packet);
                stripPrefix(input, lookahead);
                if (continuity != null) continuity.beginSegment();
            } else {
                throw new IOException("Invalid TS packet boundary without a PNG prefix");
            }
        }
    }

    private static void stripPrefix(PushbackInputStream input, byte[] lookahead) throws IOException {
        int count = readFully(input, lookahead);
        if (count < PNG.length || !pngSignature(lookahead)) {
            throw new IOException("Expected a PNG prefix at the segment boundary");
        }
        int lastCandidate = Math.min(MAX_PREFIX_BYTES, count - CONFIRM_PACKETS * PACKET_BYTES);
        for (int offset = PNG.length; offset <= lastCandidate; offset++) {
            checkInterrupted();
            boolean confirmed = true;
            for (int index = 0; index < CONFIRM_PACKETS; index++) {
                int packetOffset = offset + index * PACKET_BYTES;
                if (!validPacket(lookahead, packetOffset)) {
                    // Do not mistake the next wrapped segment for this segment's start,
                    // dropping the 1-4 otherwise valid packets already found here.
                    if (index > 0 && pngSignature(lookahead, packetOffset)) {
                        throw new IOException("PNG-wrapped segment contains fewer than five confirmed TS packets");
                    }
                    confirmed = false;
                    break;
                }
            }
            if (confirmed) {
                input.unread(lookahead, offset, count - offset);
                return;
            }
        }
        throw new IOException("No confirmed 188-byte TS sequence within the PNG prefix scan limit");
    }

    private static final class ContinuityState {
        private static final int PID_COUNT = 8192;
        private static final int NULL_PID = 0x1fff;

        private final byte[] lastOutputCc = new byte[PID_COUNT];
        private final boolean[] hasLast = new boolean[PID_COUNT];
        private final byte[] segmentOffset = new byte[PID_COUNT];
        private final int[] offsetEpoch = new int[PID_COUNT];
        private int epoch;

        void beginSegment() {
            if (epoch == Integer.MAX_VALUE) {
                java.util.Arrays.fill(offsetEpoch, 0);
                epoch = 1;
            } else {
                epoch++;
            }
        }

        void rewrite(byte[] packet) {
            int pid = ((packet[1] & 0x1f) << 8) | (packet[2] & 0xff);
            if (pid == NULL_PID) return;

            int inputCc = packet[3] & 0x0f;
            int adaptationControl = (packet[3] >>> 4) & 3;
            if (declaresDiscontinuity(packet, adaptationControl)) {
                segmentOffset[pid] = 0;
                offsetEpoch[pid] = epoch;
                lastOutputCc[pid] = (byte) inputCc;
                hasLast[pid] = true;
                return;
            }

            if (offsetEpoch[pid] != epoch) {
                int offset = 0;
                if (hasLast[pid]) {
                    boolean hasPayload = adaptationControl == 1 || adaptationControl == 3;
                    int expectedCc = (lastOutputCc[pid] + (hasPayload ? 1 : 0)) & 0x0f;
                    offset = (expectedCc - inputCc) & 0x0f;
                }
                segmentOffset[pid] = (byte) offset;
                offsetEpoch[pid] = epoch;
            }

            int outputCc = (inputCc + segmentOffset[pid]) & 0x0f;
            packet[3] = (byte) ((packet[3] & 0xf0) | outputCc);
            lastOutputCc[pid] = (byte) outputCc;
            hasLast[pid] = true;
        }

        private static boolean declaresDiscontinuity(byte[] packet, int adaptationControl) {
            if (adaptationControl != 2 && adaptationControl != 3) return false;
            int adaptationLength = packet[4] & 0xff;
            return adaptationLength > 0 && (packet[5] & 0x80) != 0;
        }
    }

    private static boolean pngSignature(byte[] bytes) {
        return pngSignature(bytes, 0);
    }

    private static boolean pngSignature(byte[] bytes, int offset) {
        for (int index = 0; index < PNG.length; index++) {
            if (bytes[offset + index] != PNG[index]) return false;
        }
        return true;
    }

    private static boolean validPacket(byte[] bytes, int offset) {
        if (bytes[offset] != 0x47 || (bytes[offset + 1] & 0x80) != 0) return false;
        int adaptationControl = (bytes[offset + 3] >>> 4) & 3;
        if (adaptationControl == 0) return false;
        if (adaptationControl == 1) return true;
        int adaptationLength = bytes[offset + 4] & 0xff;
        // Adaptation-only must occupy all 184 bytes after the header (length byte included).
        // Adaptation + payload must leave at least one byte for the payload.
        return adaptationControl == 2 ? adaptationLength == 183 : adaptationLength <= 182;
    }

    private static int readFully(InputStream input, byte[] bytes) throws IOException {
        int count = 0;
        while (count < bytes.length) {
            checkInterrupted();
            int read = input.read(bytes, count, bytes.length - count);
            if (read == -1) break;
            if (read == 0) {
                checkInterrupted();
                int single = input.read();
                if (single == -1) break;
                bytes[count++] = (byte) single;
            } else {
                count += read;
            }
        }
        return count;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("PNG-wrapped TS cleaning was interrupted");
        }
    }
}
