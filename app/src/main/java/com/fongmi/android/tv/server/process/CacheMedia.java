package com.fongmi.android.tv.server.process;

import static fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT;
import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import com.fongmi.android.tv.cache.CacheMetadata;
import com.fongmi.android.tv.cache.CachePaths;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.server.FileResponder;
import com.fongmi.android.tv.server.impl.Process;

import java.io.File;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/** Serves completed downloads to LAN players without exposing filesystem paths. */
public final class CacheMedia implements Process {

    private static final String PREFIX = "/cache/";
    private static final Pattern ROUTE = Pattern.compile("^/cache/([0-9a-f]{64})$");

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        // Claim every sub-path so malformed cache media URLs cannot fall through to
        // the legacy /cache key-value endpoint, which otherwise returns HTTP 200.
        return url.startsWith(PREFIX);
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        Matcher matcher = ROUTE.matcher(url);
        if (!matcher.matches()) return notFound();
        Method method = session.getMethod();
        if (method != Method.GET && method != Method.HEAD) return methodNotAllowed();
        try {
            CacheMetadata metadata = AppDatabase.get().getCacheMetadataDao().findCompleted(matcher.group(1));
            if (metadata == null || !metadata.isCompleted()) return notFound();
            String localPath = metadata.getLocalPath();
            if (localPath == null || localPath.trim().isEmpty()) return notFound();
            File file = CachePaths.requireReadableFile(new File(localPath));
            return FileResponder.respond(method, session.getHeaders(), file, metadata.getMimeType());
        } catch (Exception e) {
            return notFound();
        }
    }

    private static Response notFound() {
        return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "");
    }

    private static Response methodNotAllowed() {
        Response response = newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "");
        response.addHeader("Allow", "GET, HEAD");
        return response;
    }
}
