package com.fongmi.android.tv.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class CacheKeyTest {

    @Test
    public void stableMediaIdentityWinsOverRotatingUrlAndHeaders() {
        String first = CacheKey.create("site@@@vod@@@1", "line-a", "episode-1", "https://cdn.example/a.mp4?token=one", Map.of("Authorization", "one"));
        String second = CacheKey.create("site@@@vod@@@1", "line-a", "episode-1", "https://cdn.example/a.mp4?token=two", Map.of("Authorization", "two"));

        assertEquals(first, second);
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    public void stableMediaIdentityIncludesSourceAndEpisode() {
        String base = CacheKey.fromMedia("site@@@vod@@@1", "line-a", "episode-1");

        assertNotEquals(base, CacheKey.fromMedia("site@@@vod@@@1", "line-b", "episode-1"));
        assertNotEquals(base, CacheKey.fromMedia("site@@@vod@@@1", "line-a", "episode-2"));
    }

    @Test
    public void urlFallbackNormalizesSchemeHostDefaultPortAndFragment() {
        String first = CacheKey.fromUrl(" HTTPS://Example.COM:443/video/file.mp4?a=1#ignored ", Map.of());
        String second = CacheKey.fromUrl("https://example.com/video/file.mp4?a=1", Map.of());

        assertEquals(first, second);
        assertEquals("https://example.com/video/file.mp4?a=1", CacheKey.normalizeUrl("HTTPS://Example.COM:443/video/file.mp4?a=1#ignored"));
    }

    @Test
    public void urlFallbackCanonicalizesHeaderOrderAndCase() {
        Map<String, String> firstHeaders = new LinkedHashMap<>();
        firstHeaders.put("User-Agent", " FongMi ");
        firstHeaders.put("Referer", "https://example.com/");
        Map<String, String> secondHeaders = new LinkedHashMap<>();
        secondHeaders.put("referer", "https://example.com/");
        secondHeaders.put("user-agent", "FongMi");

        String first = CacheKey.fromUrl("https://cdn.example/video.mp4", firstHeaders);
        String second = CacheKey.fromUrl("https://cdn.example/video.mp4", secondHeaders);

        assertEquals(first, second);
        assertNotEquals(first, CacheKey.fromUrl("https://cdn.example/video.mp4", Map.of("User-Agent", "Other")));
    }

    @Test
    public void urlFallbackPreservesQueryOrdering() {
        String first = CacheKey.fromUrl("https://cdn.example/video.mp4?a=1&b=2", Map.of());
        String second = CacheKey.fromUrl("https://cdn.example/video.mp4?b=2&a=1", Map.of());

        assertNotEquals(first, second);
    }

    @Test
    public void missingIdentityAndUrlIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CacheKey.create("", "", "", " ", Map.of()));
    }
}
