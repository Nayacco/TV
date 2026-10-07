package com.fongmi.android.tv.server;

import static fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT;
import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/** Streams a local file with HTTP cache validation and single-range support. */
public final class FileResponder {

    private static final String DEFAULT_MIME = "application/octet-stream";

    private FileResponder() {
    }

    public static Response respond(Method method, Map<String, String> headers, File file, String mime) throws IOException {
        if (method != Method.GET && method != Method.HEAD) return methodNotAllowed();
        long fileLength = file.length();
        String resolvedMime = resolveMimeType(file, mime);
        String etag = etag(file, fileLength);
        String ifNoneMatch = header(headers, "if-none-match");
        if (ifNoneMatch != null && (ifNoneMatch.equals("*") || ifNoneMatch.equals(etag))) return notModified(resolvedMime, etag);
        Range range = Range.from(fileLength, header(headers, "range"), header(headers, "if-range"), etag);
        if (!range.valid()) return rangeNotSatisfiable(fileLength);
        return file(method, file, resolvedMime, fileLength, etag, range);
    }

    public static String resolveMimeType(File file, String mime) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".ts")) return "video/mp2t";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (mime != null && !mime.trim().isEmpty()) return mime;
        String detected = fi.iki.elonen.NanoHTTPD.getMimeTypeForFile(file.getName());
        return detected == null || detected.trim().isEmpty() ? DEFAULT_MIME : detected;
    }

    private static Response file(Method method, File file, String mime, long fileLength, String etag, Range range) throws IOException {
        InputStream input;
        if (method == Method.HEAD) {
            input = new ByteArrayInputStream(new byte[0]);
        } else {
            FileInputStream stream = new FileInputStream(file);
            stream.getChannel().position(range.start());
            input = stream;
        }
        Status status = range.requested() ? Status.PARTIAL_CONTENT : Status.OK;
        Response response = newFixedLengthResponse(status, mime, input, range.length());
        if (range.requested()) response.addHeader("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + fileLength);
        response.addHeader("Accept-Ranges", "bytes");
        response.addHeader("ETag", etag);
        return response;
    }

    private static Response notModified(String mime, String etag) {
        Response response = newFixedLengthResponse(Status.NOT_MODIFIED, normalizeMime(mime), "");
        response.addHeader("ETag", etag);
        response.addHeader("Accept-Ranges", "bytes");
        return response;
    }

    private static Response rangeNotSatisfiable(long fileLength) {
        Response response = newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "");
        response.addHeader("Content-Range", "bytes */" + fileLength);
        response.addHeader("Accept-Ranges", "bytes");
        return response;
    }

    private static Response methodNotAllowed() {
        Response response = newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "");
        response.addHeader("Allow", "GET, HEAD");
        return response;
    }

    private static String normalizeMime(String mime) {
        return mime == null || mime.trim().isEmpty() ? DEFAULT_MIME : mime;
    }

    private static String etag(File file, long fileLength) throws IOException {
        CRC32 crc = new CRC32();
        String value = file.getCanonicalPath() + file.lastModified() + fileLength;
        crc.update(value.getBytes(StandardCharsets.UTF_8));
        return '"' + Long.toHexString(crc.getValue()) + '"';
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null || headers.isEmpty()) return null;
        String value = headers.get(name);
        if (value != null) return value;
        for (Map.Entry<String, String> entry : headers.entrySet()) if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        return null;
    }

    static record Range(long start, long end, long length, boolean valid, boolean requested) {

        static Range invalid() {
            return new Range(0, 0, 0, false, false);
        }

        static Range from(long fileLength, String rangeHeader, String ifRange, String etag) {
            if (ifRange != null && !ifRange.equals(etag)) rangeHeader = null;
            if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) return new Range(0, fileLength - 1, fileLength, true, false);
            String range = rangeHeader.substring(6).trim();
            if (range.contains(",")) return invalid();
            String[] bounds = range.split("-", -1);
            if (bounds.length != 2) return invalid();
            String first = bounds[0].trim();
            String last = bounds[1].trim();
            try {
                if (first.isEmpty()) {
                    long suffix = Long.parseLong(last);
                    if (fileLength <= 0 || suffix <= 0) return invalid();
                    long length = Math.min(suffix, fileLength);
                    return new Range(fileLength - length, fileLength - 1, length, true, true);
                }
                long start = Long.parseLong(first);
                long end = last.isEmpty() ? fileLength - 1 : Long.parseLong(last);
                if (start < 0 || start >= fileLength || end < start) return invalid();
                end = Math.min(end, fileLength - 1);
                return new Range(start, end, end - start + 1, true, true);
            } catch (NumberFormatException e) {
                return invalid();
            }
        }
    }
}
