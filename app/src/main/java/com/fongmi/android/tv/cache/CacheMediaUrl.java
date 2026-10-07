package com.fongmi.android.tv.cache;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Maps a playback URL to the URL FluxDown should classify and download. */
public final class CacheMediaUrl {

    private CacheMediaUrl() {
    }

    public static String forDownload(String url, String mimeType) {
        String suffix = streamSuffix(mimeType);
        if (url == null || suffix.isEmpty()) return url;
        try {
            URI uri = new URI(url);
            if (!isLocalProxy(uri)) return url;
            String path = uri.getRawPath();
            if (path.equals("/proxy" + suffix)) return url;
            if (!path.equals("/proxy")) return url;
            int pathStart = url.indexOf(path, url.indexOf("://") + 3);
            if (pathStart < 0) return url;
            int pathEnd = pathStart + path.length();
            return url.substring(0, pathStart) + path + suffix + url.substring(pathEnd);
        } catch (Exception ignored) {
            return url;
        }
    }

    public static String requireSupportedDownloadUrl(String url, String mimeType) {
        String downloadUrl = forDownload(url, mimeType);
        String suffix = streamSuffix(mimeType);
        if (suffix.isEmpty() || hasStreamSuffix(downloadUrl, suffix)) return downloadUrl;
        String protocol = ".mpd".equals(suffix) ? "DASH" : "HLS";
        throw new IllegalArgumentException(protocol + " source uses an opaque URL that FluxDown cannot cache for offline playback");
    }

    public static boolean isAdaptiveStream(String url, String mimeType) {
        if (!streamSuffix(mimeType).isEmpty()) return true;
        try {
            String path = new URI(url).getPath();
            String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
            return lower.endsWith(".m3u8") || lower.endsWith(".m3u") || lower.endsWith(".mpd");
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isLocalProxy(String url) {
        if (url == null) return false;
        try {
            return isLocalProxy(new URI(url));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String onLocalServer(String url, String serverBaseUrl) {
        if (!isLocalProxy(url) || serverBaseUrl == null) return url;
        try {
            URI source = new URI(url);
            URI server = new URI(serverBaseUrl);
            if (!isHttp(server) || server.getHost() == null || server.getPort() <= 0) return url;
            StringBuilder result = new StringBuilder()
                    .append(server.getScheme()).append("://").append(server.getRawAuthority())
                    .append(source.getRawPath());
            if (source.getRawQuery() != null) result.append('?').append(source.getRawQuery());
            if (source.getRawFragment() != null) result.append('#').append(source.getRawFragment());
            return result.toString();
        } catch (Exception ignored) {
            return url;
        }
    }

    public static boolean isOnLocalServer(String url, String serverBaseUrl) {
        return url != null && url.equals(onLocalServer(url, serverBaseUrl));
    }

    public static String withSiteKey(String url, String siteKey) {
        if (url == null || siteKey == null || siteKey.isEmpty() || !isLocalProxy(url) || hasSiteKey(url)) return url;
        try {
            URI uri = new URI(url);
            int fragmentStart = url.indexOf('#');
            int insertAt = fragmentStart < 0 ? url.length() : fragmentStart;
            String beforeFragment = url.substring(0, insertAt);
            String separator = uri.getRawQuery() == null ? "?" : uri.getRawQuery().isEmpty() ? "" : "&";
            return beforeFragment + separator + "siteKey=" + encodeQueryValue(siteKey) + url.substring(insertAt);
        } catch (Exception ignored) {
            return url;
        }
    }

    public static boolean hasSiteKey(String url) {
        try {
            String query = new URI(url).getRawQuery();
            if (query == null) return false;
            for (String part : query.split("&")) {
                int equals = part.indexOf('=');
                if ((equals < 0 ? part : part.substring(0, equals)).equals("siteKey")) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String streamSuffix(String mimeType) {
        if (mimeType == null) return "";
        String normalized = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("mpegurl") || normalized.contains("m3u8")) return ".m3u8";
        if (normalized.equals("application/dash+xml")) return ".mpd";
        return "";
    }

    private static boolean isHttp(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
    }

    private static boolean hasStreamSuffix(String url, String suffix) {
        try {
            String path = new URI(url).getPath();
            String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
            if (".m3u8".equals(suffix)) return lower.endsWith(".m3u8") || lower.endsWith(".m3u");
            return lower.endsWith(suffix);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isLocalProxy(URI uri) {
        String path = uri.getRawPath();
        return isHttp(uri) && isLocalAddress(uri.getHost()) && path != null
                && (path.equals("/proxy") || path.startsWith("/proxy."));
    }

    private static boolean isLocalAddress(String host) {
        if (host == null) return false;
        String normalized = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        if (isLoopback(normalized)) return true;
        if (normalized.indexOf(':') >= 0) {
            int separator = normalized.indexOf(':');
            if (separator <= 0) return false;
            try {
                int first = Integer.parseInt(normalized.substring(0, separator), 16);
                return (first & 0xfe00) == 0xfc00 || (first & 0xffc0) == 0xfe80;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        String[] parts = normalized.split("\\.");
        if (parts.length != 4) return false;
        try {
            int[] octets = new int[4];
            for (int index = 0; index < octets.length; index++) {
                octets[index] = Integer.parseInt(parts[index]);
                if (octets[index] < 0 || octets[index] > 255) return false;
            }
            int first = octets[0];
            int second = octets[1];
            return first == 10 || first == 192 && second == 168
                    || first == 172 && second >= 16 && second <= 31
                    || first == 169 && second == 254;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
    }

    private static String encodeQueryValue(String value) {
        StringBuilder result = new StringBuilder();
        char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
            int current = item & 0xff;
            if ((current >= 'a' && current <= 'z') || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9') || current == '-' || current == '.' || current == '_' || current == '~') {
                result.append((char) current);
            } else {
                result.append('%').append(hex[current >>> 4]).append(hex[current & 0x0f]);
            }
        }
        return result.toString();
    }
}
