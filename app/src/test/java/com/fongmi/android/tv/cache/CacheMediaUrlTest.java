package com.fongmi.android.tv.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CacheMediaUrlTest {

    @Test
    public void addsHlsSuffixToLocalProxyWithoutChangingItsQuery() {
        assertEquals(
                "http://127.0.0.1:9978/proxy.m3u8?do=play&id=42#part",
                CacheMediaUrl.forDownload(
                        "http://127.0.0.1:9978/proxy?do=play&id=42#part",
                        "application/vnd.apple.mpegurl; charset=utf-8"));
    }

    @Test
    public void addsDashSuffixToLocalProxy() {
        assertEquals(
                "http://localhost:9980/proxy.mpd?do=play&id=42",
                CacheMediaUrl.forDownload(
                        "http://localhost:9980/proxy?do=play&id=42",
                        "application/dash+xml"));
    }

    @Test
    public void keepsAlreadyClassifiedAndProgressiveUrlsUnchanged() {
        String hls = "http://127.0.0.1:9978/proxy.m3u8?do=play";
        String mp4 = "http://127.0.0.1:9978/proxy?do=play&id=movie.mp4";
        assertEquals(hls, CacheMediaUrl.forDownload(hls, "application/x-mpegURL"));
        assertEquals(mp4, CacheMediaUrl.forDownload(mp4, "video/mp4"));
    }

    @Test
    public void doesNotRewriteThirdPartyOpaqueEndpoints() {
        String url = "https://media.example/play?id=42";
        assertEquals(url, CacheMediaUrl.forDownload(url, "application/x-mpegURL"));
        assertThrows(IllegalArgumentException.class,
                () -> CacheMediaUrl.requireSupportedDownloadUrl(url, "application/x-mpegURL"));
    }

    @Test
    public void addsEncodedCacheBindingWithoutChangingUpstreamRouting() {
        String source = "http://127.0.0.1:9978/proxy?do=js&id=42#part";
        String expected = "http://127.0.0.1:9978/proxy?do=js&id=42&_cacheSiteKey=%E7%AB%99%E7%82%B9%20A#part";
        assertEquals(expected, CacheMediaUrl.withProxySite(source, "站点 A"));
        assertEquals(expected, CacheMediaUrl.withProxySite(expected, "other"));
        assertTrue(CacheMediaUrl.hasProxyContext(expected));
    }

    @Test
    public void preservesNativeSiteKeyAndRecognizesItAsBound() {
        String source = "http://127.0.0.1:9978/proxy?do=m3u8&siteKey=native&url=movie.m3u8";
        assertEquals(source, CacheMediaUrl.withProxySite(source, "other"));
        assertTrue(CacheMediaUrl.hasProxyContext(source));
        assertFalse(CacheMediaUrl.hasProxyContext("http://127.0.0.1:9978/proxy?do=m3u8"));
    }

    @Test
    public void reportedM3u8ProxyKeepsItsEncodedUrlWithIndependentBinding() {
        String source = "http://127.0.0.1:9978/proxy?do=m3u8&url=https%3A%2F%2Fvip1.lz-cdn5.com%2F20220424%2F11560_e505c220%2Findex.m3u8";
        assertEquals(source.replace("/proxy?", "/proxy.m3u8?") + "&_cacheSiteKey=%E7%B3%AF%E7%B1%B3",
                CacheMediaUrl.forDownload(CacheMediaUrl.withProxySite(source, "糯米"), "application/x-mpegURL"));
    }

    @Test
    public void recognizesLanProxyAndRoutesItToCurrentLoopbackServer() {
        String source = "http://192.168.50.12:9978/proxy?do=js&id=42#part";
        String keyed = CacheMediaUrl.withProxySite(source, "lan");
        String local = CacheMediaUrl.onLocalServer(keyed, "http://127.0.0.1:9981");
        assertEquals("http://127.0.0.1:9981/proxy?do=js&id=42&_cacheSiteKey=lan#part", local);
        assertEquals("http://127.0.0.1:9981/proxy.m3u8?do=js&id=42&_cacheSiteKey=lan#part",
                CacheMediaUrl.forDownload(local, "application/x-mpegURL"));
    }

    @Test
    public void recognizesPrivateIpv6WithoutMisclassifyingPublicHostnames() {
        assertTrue(CacheMediaUrl.isLocalProxy("http://[fd12:3456::1]:9978/proxy?do=js"));
        assertFalse(CacheMediaUrl.isLocalProxy("https://fcdn.example.com/proxy?do=play"));
    }

    @Test
    public void detectsWhenPersistedProxyEndpointNoLongerMatchesServer() {
        String current = "http://127.0.0.1:9978/proxy?do=js";
        String lan = "http://192.168.50.12:9978/proxy?do=js";
        assertTrue(CacheMediaUrl.isOnLocalServer(current, "http://127.0.0.1:9978"));
        assertFalse(CacheMediaUrl.isOnLocalServer(lan, "http://127.0.0.1:9978"));
        assertFalse(CacheMediaUrl.isOnLocalServer(current, "http://127.0.0.1:9979"));
    }

    @Test
    public void recognizesAdaptiveStreamsFromMimeTypeOrUrl() {
        assertTrue(CacheMediaUrl.isAdaptiveStream("http://127.0.0.1:9978/proxy", "application/x-mpegURL"));
        assertTrue(CacheMediaUrl.isAdaptiveStream("https://media.example/movie.mpd?token=1", null));
        assertFalse(CacheMediaUrl.isAdaptiveStream("https://media.example/movie.mp4", "video/mp4"));
    }
}
