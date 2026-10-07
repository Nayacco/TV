package com.fongmi.android.tv.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class CacheFileValidatorTest {

    @Test
    public void rejectsHlsManifestWithBomAndWhitespace() throws Exception {
        Path path = write(new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf}, "\n  #EXTM3U\n#EXTINF:6,\nsegment.ts\n");
        try {
            assertEquals(CacheFileValidator.HLS_MIME_TYPE, CacheFileValidator.streamingMimeType(path.toFile()));
            IOException error = assertThrows(IOException.class, () -> CacheFileValidator.requireOfflineMedia(path.toFile()));
            assertEquals(CacheFileValidator.INCOMPLETE_MESSAGE, error.getMessage());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void rejectsDashManifestAfterXmlDeclarationAndComment() throws Exception {
        Path path = write(new byte[0], " <?xml version=\"1.0\"?>\n<!-- DASH -->\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"></MPD>");
        try {
            assertTrue(CacheFileValidator.isStreamingManifest(path.toFile()));
            assertEquals(CacheFileValidator.DASH_MIME_TYPE, CacheFileValidator.streamingMimeType(path.toFile()));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void acceptsBinaryMedia() throws Exception {
        Path path = Files.createTempFile("cache-media", ".ts");
        Files.write(path, new byte[]{0x47, 0x40, 0x00, 0x10, 0x00, 0x01});
        try {
            File file = CacheFileValidator.requireOfflineMedia(path.toFile());
            assertEquals(path.toFile(), file);
            assertFalse(CacheFileValidator.isStreamingManifest(file));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void rejectsEmptyFile() throws Exception {
        Path path = Files.createTempFile("cache-empty", ".mp4");
        try {
            IOException error = assertThrows(IOException.class, () -> CacheFileValidator.requireOfflineMedia(path.toFile()));
            assertEquals(CacheFileValidator.EMPTY_MESSAGE, error.getMessage());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void rejectsVideoWithUnmergedAudioSidecar() throws Exception {
        Path directory = Files.createTempDirectory("cache-split-media");
        Path video = directory.resolve("movie.mp4");
        Path audio = directory.resolve("movie.audio.m4a");
        Files.write(video, new byte[]{0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70});
        Files.write(audio, new byte[]{0x01});
        try {
            IOException error = assertThrows(IOException.class,
                    () -> CacheFileValidator.requireOfflineMedia(video.toFile()));
            assertEquals(CacheFileValidator.SEPARATE_AUDIO_MESSAGE, error.getMessage());
        } finally {
            Files.deleteIfExists(audio);
            Files.deleteIfExists(video);
            Files.deleteIfExists(directory);
        }
    }

    private static Path write(byte[] prefix, String text) throws IOException {
        Path path = Files.createTempFile("cache-manifest", ".tmp");
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] value = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, value, 0, prefix.length);
        System.arraycopy(body, 0, value, prefix.length, body.length);
        return Files.write(path, value);
    }
}
