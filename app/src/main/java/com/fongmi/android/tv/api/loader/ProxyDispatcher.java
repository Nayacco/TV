package com.fongmi.android.tv.api.loader;

import java.util.HashMap;
import java.util.Map;

/** Keeps cache source binding separate from the upstream proxy's routing parameters. */
public final class ProxyDispatcher {

    public static final String CACHE_SITE_KEY = "_cacheSiteKey";

    public interface Backend {
        Object[] site(String key, Map<String, String> params) throws Exception;

        Object[] jar(String key, Map<String, String> params) throws Exception;

        Object[] recent(Map<String, String> params) throws Exception;
    }

    private ProxyDispatcher() {
    }

    public static Object[] dispatch(Map<String, String> original, Backend backend) throws Exception {
        Map<String, String> params = new HashMap<>(original);
        String cacheSiteKey = params.remove(CACHE_SITE_KEY);
        if (params.containsKey("siteKey")) {
            String key = params.get("siteKey");
            Object[] response = backend.site(key, params);
            // Older cache builds added siteKey even to shared JAR proxy requests.
            // A null instance response means this handler did not implement that route.
            return response != null ? response : backend.jar(key, params);
        }
        if (cacheSiteKey == null || cacheSiteKey.isEmpty()) return backend.recent(params);
        String action = params.get("do");
        return "js".equals(action) || "py".equals(action)
                ? backend.site(cacheSiteKey, params) : backend.jar(cacheSiteKey, params);
    }
}
