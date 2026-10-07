package com.fongmi.android.tv.cache;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record CacheRequest(String cacheKey, String title, String poster, String sourceName, String episodeName,
                           String mediaUrl, Map<String, String> headers, String mimeType, String outputFileName) {

    public CacheRequest {
        headers = headers == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    public static CacheRequest from(History history, String displayTitle, String mediaUrl, Map<String, String> headers, String mimeType) {
        if (history == null) throw new IllegalArgumentException("Missing playback context");
        if (TextUtils.isEmpty(mediaUrl)) throw new IllegalArgumentException("Missing media URL");
        String cacheKey = CacheKey.fromMedia(history.getKey(), history.getVodFlag(), history.getEpisodeUrl());
        String title = TextUtils.isEmpty(history.getVodName()) ? displayTitle : history.getVodName();
        if (TextUtils.isEmpty(title)) title = "video";
        String fileTitle = title + (TextUtils.isEmpty(history.getVodRemarks()) ? "" : "-" + history.getVodRemarks());
        mediaUrl = CacheMediaUrl.withSiteKey(mediaUrl, siteKey(history));
        return new CacheRequest(
                cacheKey,
                title,
                history.getVodPic(),
                sourceName(history),
                history.getVodRemarks(),
                mediaUrl,
                headers,
                mimeType,
                outputName(fileTitle, cacheKey, mediaUrl));
    }

    private static String sourceName(History history) {
        try {
            return history.getSiteName();
        } catch (Exception ignored) {
            return history.getSiteKey();
        }
    }

    private static String siteKey(History history) {
        try {
            String siteKey = history.getSiteKey();
            return VodConfig.get().getSite(siteKey).isEmpty() ? "" : siteKey;
        } catch (Exception ignored) {
            return "";
        }
    }

    static String outputName(String title, String cacheKey, String url) {
        String safe = title == null ? "video" : title
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-")
                .replaceAll("\\s+", " ")
                .replaceAll("[-. ]+$", "")
                .trim();
        if (safe.isEmpty()) safe = "video";
        if (safe.length() > 72) safe = safe.substring(0, 72).trim();
        String extension = extension(url);
        // An empty name tells FluxDown to use Content-Disposition / the final response URL.
        // Guessing .mp4 here can both misclassify opaque URLs and disable upstream probing.
        return extension.isEmpty() ? "" : safe + "-" + cacheKey.substring(0, 12) + extension;
    }

    private static String extension(String url) {
        try {
            String path = new URI(url).getPath();
            String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
            for (String extension : new String[]{".mp4", ".mkv", ".webm", ".mov", ".avi", ".flv", ".m4v", ".ts"}) {
                if (lower.endsWith(extension)) return extension;
            }
            if (lower.endsWith(".m3u8")) return ".ts";
        } catch (Exception ignored) {
        }
        return "";
    }
}
