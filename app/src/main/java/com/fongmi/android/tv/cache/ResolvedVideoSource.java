package com.fongmi.android.tv.cache;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ResolvedVideoSource(Type type, String cacheKey, String resolvedUrl, String localPath,
                                  Map<String, String> headers, String mimeType) {

    public enum Type { LOCAL_CACHE, REMOTE_URL }

    public ResolvedVideoSource {
        headers = headers == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    public boolean isLocal() {
        return type == Type.LOCAL_CACHE;
    }
}
