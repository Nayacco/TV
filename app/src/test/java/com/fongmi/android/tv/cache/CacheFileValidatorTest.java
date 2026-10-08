package com.fongmi.android.tv.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

    @Test
    public void identifiesMp4ByFtypRegardlessOfFilename() throws Exception {
        byte[] mp4 = new byte[]{0, 0, 0, 20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
                0, 0, 0, 0, 'm', 'p', '4', '2'};
        assertContainer(".mp4", mp4, CacheFileValidator.MP4_MIME_TYPE);
        assertContainer(".ts", mp4, CacheFileValidator.MP4_MIME_TYPE);
        assertContainer(".bin", mp4, CacheFileValidator.MP4_MIME_TYPE);
    }

    @Test
    public void identifiesTsByRepeatedPacketsRegardlessOfFilename() throws Exception {
        byte[] ts = transportStream(188, 0);
        assertContainer(".ts", ts, CacheFileValidator.TS_MIME_TYPE);
        assertContainer(".mp4", ts, CacheFileValidator.TS_MIME_TYPE);
        assertContainer(".bin", ts, CacheFileValidator.TS_MIME_TYPE);
    }

    @Test
    public void identifiesTimestampedAndErrorCorrectedTsPackets() throws Exception {
        assertContainer(".m2ts", transportStream(192, 4), CacheFileValidator.TS_MIME_TYPE);
        assertContainer(".ts", transportStream(204, 0), CacheFileValidator.TS_MIME_TYPE);
    }

    @Test
    public void ignoresManifestsAndInsufficientMediaSignatures() throws Exception {
        assertContainer(".mp4", "#EXTM3U\n# ftypisom\nsegment.ts".getBytes(StandardCharsets.UTF_8), null);
        assertContainer(".ts", "<MPD><BaseURL>ftypisom</BaseURL></MPD>".getBytes(StandardCharsets.UTF_8), null);
        assertContainer(".ts", new byte[]{0x47, 0x40, 0, 0x10}, null);
        assertContainer(".mp4", new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p'}, null);
        assertContainer(".mkv", new byte[]{0x1a, 0x45, (byte) 0xdf, (byte) 0xa3}, null);
    }

    @Test
    public void skipsLegalLeadingPaddingButDoesNotSearchArbitraryPayload() throws Exception {
        byte[] value = new byte[]{0, 0, 0, 8, 'f', 'r', 'e', 'e',
                0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};
        assertContainer(".ts", value, CacheFileValidator.MP4_MIME_TYPE);
        value[4] = 'm';
        value[5] = 'd';
        value[6] = 'a';
        value[7] = 't';
        assertContainer(".mp4", value, null);
    }

    @Test
    public void doesNotClassifyIsoBmffAudioOrImagesAsMp4Video() throws Exception {
        byte[] value = new byte[]{0, 0, 0, 16, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 0, 0, 0, 0};
        assertContainer(".mp4", value, "audio/mp4");
        value[8] = 'a';
        value[9] = 'v';
        value[10] = 'i';
        value[11] = 'f';
        assertContainer(".mp4", value, null);
    }

    private static void assertContainer(String extension, byte[] value, String expected) throws Exception {
        Path path = Files.createTempFile("cache-container", extension);
        Files.write(path, value);
        try {
            if (expected == null) assertNull(CacheFileValidator.mediaMimeType(path.toFile()));
            else assertEquals(expected, CacheFileValidator.mediaMimeType(path.toFile()));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static byte[] transportStream(int packetSize, int offset) {
        byte[] value = new byte[5 * packetSize];
        for (int packet = 0; packet < 5; packet++) {
            int start = offset + packet * packetSize;
            value[start] = 0x47;
            value[start + 1] = 0x40;
            value[start + 3] = 0x10;
        }
        return value;
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
