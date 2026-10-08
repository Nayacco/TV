package com.fongmi.android.tv.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class FileResponderTest {

    private static final String ETAG = "\"etag\"";

    @Test
    public void noRangeReturnsWholeFile() {
        FileResponder.Range range = FileResponder.Range.from(100, null, null, ETAG);

        assertTrue(range.valid());
        assertFalse(range.requested());
        assertEquals(0, range.start());
        assertEquals(99, range.end());
        assertEquals(100, range.length());
    }

    @Test
    public void parsesBoundedOpenAndSuffixRanges() {
        assertRange(FileResponder.Range.from(100, "bytes=10-19", null, ETAG), 10, 19, 10);
        assertRange(FileResponder.Range.from(100, "bytes=90-", null, ETAG), 90, 99, 10);
        assertRange(FileResponder.Range.from(100, "bytes=-7", null, ETAG), 93, 99, 7);
        assertRange(FileResponder.Range.from(100, "bytes=90-200", null, ETAG), 90, 99, 10);
    }

    @Test
    public void rejectsUnsatisfiableAndMultiRanges() {
        assertFalse(FileResponder.Range.from(100, "bytes=100-", null, ETAG).valid());
        assertFalse(FileResponder.Range.from(100, "bytes=20-10", null, ETAG).valid());
        assertFalse(FileResponder.Range.from(100, "bytes=0-1,4-5", null, ETAG).valid());
        assertFalse(FileResponder.Range.from(0, "bytes=0-0", null, ETAG).valid());
        assertFalse(FileResponder.Range.from(0, "bytes=-1", null, ETAG).valid());
    }

    @Test
    public void mismatchedIfRangeFallsBackToWholeFile() {
        FileResponder.Range range = FileResponder.Range.from(100, "bytes=10-19", "\"old\"", ETAG);

        assertTrue(range.valid());
        assertFalse(range.requested());
        assertEquals(100, range.length());
    }

    @Test
    public void finalMediaExtensionOverridesStaleMimeMetadata() {
        assertEquals("video/mp2t", FileResponder.resolveMimeType(new File("movie.ts"), "video/mp4"));
        assertEquals("video/mp4", FileResponder.resolveMimeType(new File("movie.mp4"), "video/mp2t"));
    }

    @Test
    public void actualMp4OverridesTsFilenameAndManifestMetadata() throws Exception {
        Path path = Files.createTempFile("file-responder-container", ".ts");
        Files.write(path, new byte[]{0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0});
        try {
            assertEquals("video/mp4", FileResponder.resolveMimeType(path.toFile(), "application/x-mpegURL"));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void actualTsOverridesMp4FilenameAndMetadata() throws Exception {
        Path path = Files.createTempFile("file-responder-container", ".mp4");
        byte[] value = new byte[5 * 188];
        for (int packet = 0; packet < 5; packet++) {
            value[packet * 188] = 0x47;
            value[packet * 188 + 3] = 0x10;
        }
        Files.write(path, value);
        try {
            assertEquals("video/mp2t", FileResponder.resolveMimeType(path.toFile(), "video/mp4"));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void unknownDirectFormatPreservesSuppliedMime() throws Exception {
        Path path = Files.createTempFile("file-responder-container", ".bin");
        Files.write(path, new byte[]{1, 2, 3, 4});
        try {
            assertEquals("video/x-matroska", FileResponder.resolveMimeType(path.toFile(), "video/x-matroska"));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void headReturnsHeadersWithoutOpeningAFileBody() throws Exception {
        Path path = Files.createTempFile("file-responder", ".mp4");
        Files.write(path, new byte[]{1, 2, 3, 4});
        Response response = FileResponder.respond(Method.HEAD, Collections.emptyMap(), path.toFile(), null);
        try {
            assertEquals(Status.OK, response.getStatus());
            assertEquals("video/mp4", response.getMimeType());
            assertEquals("bytes", response.getHeader("Accept-Ranges"));
            assertTrue(response.getHeader("ETag").startsWith("\""));
            assertEquals(-1, response.getData().read());
            assertNull(response.getHeader("Content-Length"));
        } finally {
            response.close();
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void getRangeStartsAtRequestedOffsetAndSetsPartialHeaders() throws Exception {
        Path path = Files.createTempFile("file-responder", ".ts");
        Files.write(path, new byte[]{0, 1, 2, 3, 4, 5});
        Map<String, String> headers = new HashMap<>();
        headers.put("Range", "bytes=2-4");
        Response response = FileResponder.respond(Method.GET, headers, path.toFile(), null);
        try {
            assertEquals(Status.PARTIAL_CONTENT, response.getStatus());
            assertEquals("video/mp2t", response.getMimeType());
            assertEquals("bytes 2-4/6", response.getHeader("Content-Range"));
            assertArrayEquals(new byte[]{2, 3, 4}, response.getData().readNBytes(3));
            assertNull(response.getHeader("Content-Length"));
        } finally {
            response.close();
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void invalidRangeReturns416WithFileLength() throws Exception {
        Path path = Files.createTempFile("file-responder", ".mp4");
        Files.write(path, new byte[]{1, 2, 3});
        Response response = FileResponder.respond(Method.GET, Collections.singletonMap("range", "bytes=3-"), path.toFile(), null);
        try {
            assertEquals(Status.RANGE_NOT_SATISFIABLE, response.getStatus());
            assertEquals("bytes */3", response.getHeader("Content-Range"));
            assertEquals("bytes", response.getHeader("Accept-Ranges"));
        } finally {
            response.close();
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void unsupportedMethodReturns405BeforeReadingFile() throws Exception {
        Response response = FileResponder.respond(Method.POST, Collections.emptyMap(), new File("not-used"), null);
        try {
            assertEquals(Status.METHOD_NOT_ALLOWED, response.getStatus());
            assertEquals("GET, HEAD", response.getHeader("Allow"));
        } finally {
            response.close();
        }
    }

    @Test
    public void headWireResponseHasOneContentLengthAndNoBody() throws Exception {
        Path path = Files.createTempFile("file-responder", ".mp4");
        Files.write(path, new byte[]{1, 2, 3, 4});
        try {
            byte[] wire = send(FileResponder.respond(Method.HEAD, Collections.emptyMap(), path.toFile(), null), Method.HEAD);
            String text = new String(wire, StandardCharsets.ISO_8859_1);
            int body = text.indexOf("\r\n\r\n") + 4;
            assertEquals(1, occurrences(text, "Content-Length: 4\r\n"));
            assertEquals(text.length(), body);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void partialWireResponseHasOneContentLengthAndRequestedBytes() throws Exception {
        Path path = Files.createTempFile("file-responder", ".ts");
        Files.write(path, new byte[]{0, 1, 2, 3, 4, 5});
        try {
            Response response = FileResponder.respond(Method.GET, Collections.singletonMap("range", "bytes=2-4"), path.toFile(), null);
            byte[] wire = send(response, Method.GET);
            int body = indexOf(wire, new byte[]{'\r', '\n', '\r', '\n'}) + 4;
            String headers = new String(wire, 0, body, StandardCharsets.ISO_8859_1);
            assertEquals(1, occurrences(headers, "Content-Length: 3\r\n"));
            assertArrayEquals(new byte[]{2, 3, 4}, Arrays.copyOfRange(wire, body, wire.length));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void assertRange(FileResponder.Range range, long start, long end, long length) {
        assertTrue(range.valid());
        assertTrue(range.requested());
        assertEquals(start, range.start());
        assertEquals(end, range.end());
        assertEquals(length, range.length());
    }

    private static byte[] send(Response response, Method requestMethod) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.setRequestMethod(requestMethod);
        java.lang.reflect.Method send = Response.class.getDeclaredMethod("send", java.io.OutputStream.class);
        send.setAccessible(true);
        send.invoke(response, output);
        return output.toByteArray();
    }

    private static int indexOf(byte[] value, byte[] target) {
        for (int i = 0; i <= value.length - target.length; i++) {
            boolean match = true;
            for (int j = 0; j < target.length; j++) match &= value[i + j] == target[j];
            if (match) return i;
        }
        return -1;
    }

    private static int occurrences(String value, String target) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(target, index)) >= 0; index += target.length()) count++;
        return count;
    }
}
