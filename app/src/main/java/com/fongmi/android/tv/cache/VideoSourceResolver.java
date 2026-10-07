package com.fongmi.android.tv.cache;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.server.Server;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The one completed-cache gate shared by local playback, cache-list playback and DLNA. */
public final class VideoSourceResolver {

    private VideoSourceResolver() {
    }

    public static String cacheKey(History history) {
        if (history == null) return "";
        return CacheKey.fromMedia(history.getKey(), history.getVodFlag(), history.getEpisodeUrl());
    }

    public static ResolvedVideoSource playback(History history, String remoteUrl, Map<String, String> headers) {
        return resolve(cacheKey(history), remoteUrl, headers, false);
    }

    public static ResolvedVideoSource cast(History history, String remoteUrl, Map<String, String> headers) {
        return resolve(cacheKey(history), remoteUrl, headers, true);
    }

    public static ResolvedVideoSource cached(String cacheKey) {
        return resolve(cacheKey, "", Collections.emptyMap(), false);
    }

    private static ResolvedVideoSource resolve(String cacheKey, String remoteUrl, Map<String, String> headers, boolean cast) {
        CacheMetadata metadata = find(cacheKey, remoteUrl);
        String resolvedKey = metadata == null ? cacheKey : metadata.getCacheKey();
        String fallbackUrl = remoteUrl;
        Map<String, String> fallbackHeaders = headers;
        if (cast && metadata != null && localFile(remoteUrl) != null) {
            fallbackUrl = metadata.getOriginalUrl();
            fallbackHeaders = CacheRepository.headers(metadata);
        }
        if (metadata == null || !metadata.isCompleted() || metadata.getLocalPath() == null) {
            return remote(resolvedKey, fallbackUrl, fallbackHeaders);
        }
        File file;
        try {
            file = CachePaths.requireReadableFile(new File(metadata.getLocalPath()));
            CacheFileValidator.requireOfflineMedia(file);
            CacheTrackValidator.requireCompleteTracks(file, metadata.getOriginalUrl(), metadata.getMimeType());
        } catch (Exception e) {
            String message = e.getMessage() == null || e.getMessage().isEmpty() ? "Cached file is missing or unreadable" : e.getMessage();
            CacheRepository.get().invalidateCompleted(metadata.getCacheKey(), message);
            return remote(resolvedKey, fallbackUrl, fallbackHeaders);
        }
        if (cast) {
            try {
                Server.get().start();
                if (!Server.get().isRunning()) return remote(resolvedKey, fallbackUrl, fallbackHeaders);
                String url = Server.get().getAddress() + "/cache/" + metadata.getCacheKey();
                if (!isLanHttpUrl(url)) return remote(resolvedKey, fallbackUrl, fallbackHeaders);
                return new ResolvedVideoSource(ResolvedVideoSource.Type.LOCAL_CACHE, metadata.getCacheKey(), url,
                        file.getAbsolutePath(), Collections.emptyMap(), metadata.getMimeType());
            } catch (RuntimeException ignored) {
                return remote(resolvedKey, fallbackUrl, fallbackHeaders);
            }
        }
        return new ResolvedVideoSource(ResolvedVideoSource.Type.LOCAL_CACHE, metadata.getCacheKey(),
                file.getAbsolutePath(), file.getAbsolutePath(), Collections.emptyMap(), metadata.getMimeType());
    }

    private static CacheMetadata find(String cacheKey, String remoteUrl) {
        try {
            CacheMetadata metadata = cacheKey == null || cacheKey.isEmpty()
                    ? null
                    : AppDatabase.get().getCacheMetadataDao().find(cacheKey);
            if (metadata != null) return metadata;
            File local = localFile(remoteUrl);
            if (local == null) return null;
            File target = local.getCanonicalFile();
            List<CacheMetadata> items = AppDatabase.get().getCacheMetadataDao().findAll();
            for (CacheMetadata item : items) {
                if (item.getLocalPath() == null) continue;
                if (target.equals(new File(item.getLocalPath()).getCanonicalFile())) return item;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static File localFile(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        try {
            File direct = new File(url);
            if (direct.isAbsolute()) return direct;
            URI uri = new URI(url);
            return "file".equalsIgnoreCase(uri.getScheme()) ? new File(uri) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isLanHttpUrl(String url) {
        try {
            URI uri = new URI(url);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isEmpty() || uri.getPort() <= 0) {
                return false;
            }
            InetAddress address = InetAddress.getByName(uri.getHost());
            return !address.isAnyLocalAddress() && !address.isLoopbackAddress() && !address.isMulticastAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static ResolvedVideoSource remote(String cacheKey, String remoteUrl, Map<String, String> headers) {
        return new ResolvedVideoSource(ResolvedVideoSource.Type.REMOTE_URL, cacheKey, remoteUrl, null, headers, null);
    }
}
