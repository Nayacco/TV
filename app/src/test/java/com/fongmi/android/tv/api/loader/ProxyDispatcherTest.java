package com.fongmi.android.tv.api.loader;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class ProxyDispatcherTest {

    private static final String MEDIA_URL = "https://vip1.lz-cdn5.com/20220424/11560_e505c220/index.m3u8";

    @Test
    public void cachedSharedM3u8UsesBoundJarWithoutChangingUpstreamUrlOrCallerMap() throws Exception {
        Map<String, String> original = request("m3u8", MEDIA_URL);
        original.put(ProxyDispatcher.CACHE_SITE_KEY, "糯米");
        Map<String, String> before = new LinkedHashMap<>(original);
        RecordingBackend backend = new RecordingBackend();

        assertSame(backend.jarResponse, ProxyDispatcher.dispatch(original, backend));

        assertEquals(1, backend.calls.size());
        Call call = backend.calls.get(0);
        assertEquals("jar", call.route);
        assertEquals("糯米", call.key);
        assertEquals(MEDIA_URL, call.params.get("url"));
        assertFalse(call.params.containsKey(ProxyDispatcher.CACHE_SITE_KEY));
        assertFalse(call.params.containsKey("siteKey"));
        assertNotSame(original, call.params);
        assertEquals(before, original);
    }

    @Test
    public void nativeSiteKeyKeepsItsRouteAndCompleteResponse() throws Exception {
        Map<String, String> original = request("m3u8", MEDIA_URL);
        original.put("siteKey", "native-source");
        original.put(ProxyDispatcher.CACHE_SITE_KEY, "different-cache-source");
        Map<String, String> before = new LinkedHashMap<>(original);
        Map<String, String> responseHeaders = Map.of("Content-Disposition", "inline; filename=playlist.m3u8");
        ByteArrayInputStream payload = new ByteArrayInputStream("#EXTM3U\n".getBytes(StandardCharsets.UTF_8));
        Object[] response = {200, "application/vnd.apple.mpegurl", payload, responseHeaders};
        RecordingBackend backend = new RecordingBackend();
        backend.siteResponse = response;

        Object[] actual = ProxyDispatcher.dispatch(original, backend);

        assertSame(response, actual);
        assertSame(payload, actual[2]);
        assertSame(responseHeaders, actual[3]);
        assertEquals(1, backend.calls.size());
        Call call = backend.calls.get(0);
        assertEquals("site", call.route);
        assertEquals("native-source", call.key);
        assertEquals("native-source", call.params.get("siteKey"));
        assertEquals("bytes=0-", call.params.get("range"));
        assertEquals(MEDIA_URL, call.params.get("url"));
        assertFalse(call.params.containsKey(ProxyDispatcher.CACHE_SITE_KEY));
        assertEquals(before, original);
    }

    @Test
    public void legacySiteKeyWithNullInstanceResponseFallsBackToTheSameSiteJar() throws Exception {
        Map<String, String> original = request("m3u8", MEDIA_URL);
        original.put("siteKey", "legacy-source");
        original.put(ProxyDispatcher.CACHE_SITE_KEY, "unrelated-source");
        Map<String, String> before = new LinkedHashMap<>(original);
        RecordingBackend backend = new RecordingBackend();

        assertSame(backend.jarResponse, ProxyDispatcher.dispatch(original, backend));

        assertEquals(2, backend.calls.size());
        assertEquals("site", backend.calls.get(0).route);
        assertEquals("legacy-source", backend.calls.get(0).key);
        assertEquals("jar", backend.calls.get(1).route);
        assertEquals("legacy-source", backend.calls.get(1).key);
        for (Call call : backend.calls) {
            assertEquals("legacy-source", call.params.get("siteKey"));
            assertEquals(MEDIA_URL, call.params.get("url"));
            assertFalse(call.params.containsKey(ProxyDispatcher.CACHE_SITE_KEY));
        }
        assertEquals(before, original);
    }

    @Test
    public void cachedJsAndPyUseTheirBoundSite() throws Exception {
        for (String action : new String[]{"js", "py"}) {
            Map<String, String> original = request(action, MEDIA_URL);
            original.put(ProxyDispatcher.CACHE_SITE_KEY, action + "-source");
            RecordingBackend backend = new RecordingBackend();
            backend.siteResponse = new Object[]{200};

            assertSame(backend.siteResponse, ProxyDispatcher.dispatch(original, backend));

            assertEquals(1, backend.calls.size());
            Call call = backend.calls.get(0);
            assertEquals("site", call.route);
            assertEquals(action + "-source", call.key);
            assertEquals(action, call.params.get("do"));
            assertFalse(call.params.containsKey(ProxyDispatcher.CACHE_SITE_KEY));
        }
    }

    @Test
    public void requestsWithoutContextKeepExistingRecentRoutingForEveryAction() throws Exception {
        for (String action : new String[]{"m3u8", "js", "py", "play", ""}) {
            Map<String, String> original = request(action, MEDIA_URL);
            Map<String, String> before = new LinkedHashMap<>(original);
            RecordingBackend backend = new RecordingBackend();

            assertSame(backend.recentResponse, ProxyDispatcher.dispatch(original, backend));

            assertEquals(1, backend.calls.size());
            assertEquals("recent", backend.calls.get(0).route);
            assertEquals(before, backend.calls.get(0).params);
            assertEquals(before, original);
        }
    }

    @Test
    public void emptyCacheContextKeepsRecentRoutingAndIsNotForwarded() throws Exception {
        Map<String, String> original = request("m3u8", MEDIA_URL);
        original.put(ProxyDispatcher.CACHE_SITE_KEY, "");
        RecordingBackend backend = new RecordingBackend();

        assertSame(backend.recentResponse, ProxyDispatcher.dispatch(original, backend));

        assertEquals(1, backend.calls.size());
        assertEquals("recent", backend.calls.get(0).route);
        assertFalse(backend.calls.get(0).params.containsKey(ProxyDispatcher.CACHE_SITE_KEY));
        assertEquals("", original.get(ProxyDispatcher.CACHE_SITE_KEY));
    }

    @Test
    public void nativeSiteExceptionDoesNotFallBackToAnotherHandler() throws Exception {
        Map<String, String> original = request("m3u8", MEDIA_URL);
        original.put("siteKey", "native-source");
        RecordingBackend backend = new RecordingBackend();
        Exception expected = new Exception("Upstream site request failed");
        backend.siteFailure = expected;

        try {
            ProxyDispatcher.dispatch(original, backend);
            fail("Expected the original site exception");
        } catch (Exception actual) {
            assertSame(expected, actual);
        }

        assertEquals(1, backend.calls.size());
        assertEquals("site", backend.calls.get(0).route);
    }

    @Test
    public void boundJarExceptionDoesNotFallBackToRecentSource() throws Exception {
        Map<String, String> original = request("m3u8", MEDIA_URL);
        original.put(ProxyDispatcher.CACHE_SITE_KEY, "bound-source");
        RecordingBackend backend = new RecordingBackend();
        Exception expected = new Exception("Bound jar request failed");
        backend.jarFailure = expected;

        try {
            ProxyDispatcher.dispatch(original, backend);
            fail("Expected the original jar exception");
        } catch (Exception actual) {
            assertSame(expected, actual);
        }

        assertEquals(1, backend.calls.size());
        assertEquals("jar", backend.calls.get(0).route);
        assertEquals("bound-source", backend.calls.get(0).key);
    }

    @Test
    public void requestsBoundToDifferentSitesDoNotPolluteEachOther() throws Exception {
        Map<String, String> first = request("m3u8", MEDIA_URL);
        first.put(ProxyDispatcher.CACHE_SITE_KEY, "source-a");
        Map<String, String> second = request("m3u8", "https://second.example/video.m3u8");
        second.put(ProxyDispatcher.CACHE_SITE_KEY, "source-b");
        RecordingBackend backend = new RecordingBackend();

        ProxyDispatcher.dispatch(first, backend);
        ProxyDispatcher.dispatch(second, backend);

        assertEquals(2, backend.calls.size());
        Call firstCall = backend.calls.get(0);
        Call secondCall = backend.calls.get(1);
        assertEquals("jar", firstCall.route);
        assertEquals("jar", secondCall.route);
        assertEquals("source-a", firstCall.key);
        assertEquals("source-b", secondCall.key);
        assertEquals(MEDIA_URL, firstCall.params.get("url"));
        assertEquals("https://second.example/video.m3u8", secondCall.params.get("url"));
        assertNotSame(firstCall.params, secondCall.params);
        assertFalse(firstCall.params.containsKey(ProxyDispatcher.CACHE_SITE_KEY));
        assertFalse(secondCall.params.containsKey(ProxyDispatcher.CACHE_SITE_KEY));
        assertEquals("source-a", first.get(ProxyDispatcher.CACHE_SITE_KEY));
        assertEquals("source-b", second.get(ProxyDispatcher.CACHE_SITE_KEY));
    }

    private static Map<String, String> request(String action, String url) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("do", action);
        params.put("url", url);
        params.put("range", "bytes=0-");
        return params;
    }

    private static final class Call {
        final String route;
        final String key;
        final Map<String, String> params;

        Call(String route, String key, Map<String, String> params) {
            this.route = route;
            this.key = key;
            this.params = params;
        }
    }

    private static final class RecordingBackend implements ProxyDispatcher.Backend {
        final List<Call> calls = new ArrayList<>();
        final Object[] jarResponse = {200, "application/vnd.apple.mpegurl", "jar-response"};
        final Object[] recentResponse = {200, "application/vnd.apple.mpegurl", "recent-response"};
        Object[] siteResponse;
        Exception siteFailure;
        Exception jarFailure;

        @Override
        public Object[] site(String key, Map<String, String> params) throws Exception {
            calls.add(new Call("site", key, params));
            if (siteFailure != null) throw siteFailure;
            return siteResponse;
        }

        @Override
        public Object[] jar(String key, Map<String, String> params) throws Exception {
            calls.add(new Call("jar", key, params));
            if (jarFailure != null) throw jarFailure;
            return jarResponse;
        }

        @Override
        public Object[] recent(Map<String, String> params) {
            calls.add(new Call("recent", null, params));
            return recentResponse;
        }
    }
}
