package com.fongmi.android.tv.cache;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CacheKey {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CacheKey() {
    }

    public static String create(String mediaId, String sourceId, String episodeId, String finalUrl, Map<String, String> headers) {
        return isBlank(mediaId) ? fromUrl(finalUrl, headers) : fromMedia(mediaId, sourceId, episodeId);
    }

    public static String fromMedia(String mediaId, String sourceId, String episodeId) {
        if (isBlank(mediaId)) throw new IllegalArgumentException("Missing media id");
        StringBuilder value = new StringBuilder("fongmi:v1\0");
        append(value, mediaId.trim());
        append(value, trim(sourceId));
        append(value, trim(episodeId));
        return sha256(value.toString());
    }

    public static String fromUrl(String finalUrl, Map<String, String> headers) {
        if (isBlank(finalUrl)) throw new IllegalArgumentException("Missing media URL");
        StringBuilder value = new StringBuilder("url:v1\0");
        append(value, normalizeUrl(finalUrl));
        normalizedHeaders(headers).forEach(header -> append(value, header));
        return sha256(value.toString());
    }

    public static String normalizeUrl(String url) {
        String value = url.trim();
        int fragment = value.indexOf('#');
        if (fragment >= 0) value = value.substring(0, fragment);
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return value;
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) port = -1;
            StringBuilder normalized = new StringBuilder();
            normalized.append(scheme).append("://");
            if (uri.getRawUserInfo() != null) normalized.append(uri.getRawUserInfo()).append('@');
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) normalized.append('[').append(host).append(']');
            else normalized.append(host);
            if (port >= 0) normalized.append(':').append(port);
            if (uri.getRawPath() != null) normalized.append(uri.getRawPath());
            if (uri.getRawQuery() != null) normalized.append('?').append(uri.getRawQuery());
            return normalized.toString();
        } catch (URISyntaxException ignored) {
            return value;
        }
    }

    private static List<String> normalizedHeaders(Map<String, String> headers) {
        List<String> result = new ArrayList<>();
        if (headers == null) return result;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) continue;
            result.add(key + ":" + entry.getValue().trim());
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private static void append(StringBuilder builder, String value) {
        builder.append(value.length()).append(':').append(value).append('\0');
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] chars = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int item = digest[i] & 0xff;
                chars[i * 2] = HEX[item >>> 4];
                chars[i * 2 + 1] = HEX[item & 0x0f];
            }
            return new String(chars);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
