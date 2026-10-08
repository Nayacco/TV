package com.fongmi.android.tv.cache;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Rejects remote streaming manifests that cannot be played without a network connection. */
public final class CacheFileValidator {

    public static final String INCOMPLETE_MESSAGE = "Only the streaming manifest was cached; media segments are not available offline";
    public static final String SEPARATE_AUDIO_MESSAGE = "Separate audio could not be merged; offline cache is incomplete";
    public static final String EMPTY_MESSAGE = "Cached media file is empty";
    public static final String HLS_MIME_TYPE = "application/x-mpegURL";
    public static final String DASH_MIME_TYPE = "application/dash+xml";
    public static final String MP4_MIME_TYPE = "video/mp4";
    public static final String TS_MIME_TYPE = "video/mp2t";
    private static final int SNIFF_BYTES = 16 * 1024;

    private CacheFileValidator() {
    }

    public static File requireOfflineMedia(File file) throws IOException {
        if (file == null || file.length() == 0) throw new IOException(EMPTY_MESSAGE);
        if (streamingMimeType(file) != null) throw new IOException(INCOMPLETE_MESSAGE);
        File audio = audioSidecar(file);
        if (audio.isFile() && audio.length() > 0) throw new IOException(SEPARATE_AUDIO_MESSAGE);
        return file;
    }

    private static File audioSidecar(File video) {
        String name = video.getName();
        int extension = name.lastIndexOf('.');
        String stem = extension > 0 ? name.substring(0, extension) : name;
        return new File(video.getParentFile(), stem + ".audio.m4a");
    }

    static boolean isStreamingManifest(File file) throws IOException {
        return streamingMimeType(file) != null;
    }

    public static String streamingMimeType(File file) throws IOException {
        return streamingMimeType(readPrefix(file));
    }

    /** Sniffs the container, not its filename or the original streaming manifest MIME. */
    public static String mediaMimeType(File file) throws IOException {
        byte[] prefix = readPrefix(file);
        if (streamingMimeType(prefix) != null) return null;
        String mp4 = mp4MimeType(prefix);
        if (mp4 != null) return mp4;
        return isTransportStream(prefix) ? TS_MIME_TYPE : null;
    }

    private static String mp4MimeType(byte[] prefix) {
        int offset = 0;
        while (offset + 8 <= prefix.length) {
            long size = ((long) (prefix[offset] & 0xff) << 24)
                    | ((long) (prefix[offset + 1] & 0xff) << 16)
                    | ((long) (prefix[offset + 2] & 0xff) << 8) | (prefix[offset + 3] & 0xff);
            String type = new String(prefix, offset + 4, 4, StandardCharsets.US_ASCII);
            if (size < 8 || size > prefix.length - offset) return null;
            if ("ftyp".equals(type)) {
                if (size < 16 || (size & 3) != 0) return null;
                String major = new String(prefix, offset + 8, 4, StandardCharsets.US_ASCII);
                if (major.equals("M4A ") || major.equals("M4B ") || major.equals("M4P ")) return "audio/mp4";
                if (major.equals("qt  ")) return "video/quicktime";
                for (int brandOffset = offset + 8; brandOffset + 4 <= offset + size; brandOffset += 4) {
                    if (brandOffset == offset + 12) continue; // Minor version, not a compatible brand.
                    String brand = new String(prefix, brandOffset, 4, StandardCharsets.US_ASCII);
                    if (brand.equals("isom") || brand.startsWith("iso") && Character.isDigit(brand.charAt(3))
                            || brand.equals("mp41") || brand.equals("mp42") || brand.equals("avc1")
                            || brand.equals("dash") || brand.equals("M4V ") || brand.equals("MSNV")) {
                        return MP4_MIME_TYPE;
                    }
                }
                return null;
            }
            // Legal padding may precede ftyp. Do not search arbitrary payload bytes for it.
            if (!type.equals("free") && !type.equals("skip") && !type.equals("wide")) return null;
            offset += (int) size;
        }
        return null;
    }

    private static boolean isTransportStream(byte[] prefix) {
        // Check repeated packet headers, not a single 0x47 byte that may occur in any file.
        for (int packetSize : new int[]{188, 192, 204}) {
            int limit = Math.min(packetSize, prefix.length - 4 * packetSize - 3);
            for (int offset = 0; offset < limit; offset++) {
                boolean matches = true;
                for (int packet = 0; packet < 5; packet++) {
                    int start = offset + packet * packetSize;
                    if ((prefix[start] & 0xff) != 0x47 || (prefix[start + 3] & 0x30) == 0) {
                        matches = false;
                        break;
                    }
                }
                if (matches) return true;
            }
        }
        return false;
    }

    static String streamingMimeType(byte[] prefix) {
        String text = trimLeading(decode(prefix));
        if (text.startsWith("#EXTM3U")) return HLS_MIME_TYPE;
        if (!text.startsWith("<")) return null;
        return startsWithMpd(text) ? DASH_MIME_TYPE : null;
    }

    private static byte[] readPrefix(File file) throws IOException {
        int capacity = (int) Math.min(Math.max(file.length(), 0), SNIFF_BYTES);
        byte[] buffer = new byte[capacity];
        int offset = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            while (offset < buffer.length) {
                int count = input.read(buffer, offset, buffer.length - offset);
                if (count < 0) break;
                offset += count;
            }
        }
        if (offset == buffer.length) return buffer;
        byte[] result = new byte[offset];
        System.arraycopy(buffer, 0, result, 0, offset);
        return result;
    }

    private static String decode(byte[] value) {
        int offset = 0;
        Charset charset = StandardCharsets.UTF_8;
        if (value.length >= 3 && (value[0] & 0xff) == 0xef && (value[1] & 0xff) == 0xbb && (value[2] & 0xff) == 0xbf) {
            offset = 3;
        } else if (value.length >= 2 && (value[0] & 0xff) == 0xff && (value[1] & 0xff) == 0xfe) {
            offset = 2;
            charset = StandardCharsets.UTF_16LE;
        } else if (value.length >= 2 && (value[0] & 0xff) == 0xfe && (value[1] & 0xff) == 0xff) {
            offset = 2;
            charset = StandardCharsets.UTF_16BE;
        }
        return new String(value, offset, value.length - offset, charset);
    }

    private static boolean startsWithMpd(String value) {
        String text = trimLeading(value);
        while (text.startsWith("<?") || text.startsWith("<!--") || text.toLowerCase(Locale.ROOT).startsWith("<!doctype")) {
            String endToken = text.startsWith("<?") ? "?>" : text.startsWith("<!--") ? "-->" : ">";
            int end = text.indexOf(endToken);
            if (end < 0) return false;
            text = trimLeading(text.substring(end + endToken.length()));
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("<")) return false;
        int end = 1;
        while (end < lower.length() && !Character.isWhitespace(lower.charAt(end)) && lower.charAt(end) != '>' && lower.charAt(end) != '/') end++;
        String name = lower.substring(1, end);
        return name.equals("mpd") || name.endsWith(":mpd");
    }

    private static String trimLeading(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        return value.substring(index);
    }
}
