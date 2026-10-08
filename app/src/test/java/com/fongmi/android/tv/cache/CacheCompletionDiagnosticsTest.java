package com.fongmi.android.tv.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

public class CacheCompletionDiagnosticsTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void preservesSystemErrorAndCompleteSourceUrl() throws Exception {
        File file = temporary.newFile("episode.mp4");
        Files.write(file.toPath(), mp4Header());
        String url = "https://video.example/opaque?url=https%3A%2F%2Fcdn.example%2Findex.m3u8&token=long-token";
        String report = describe(new IOException("Failed to instantiate extractor."), file, url,
                media -> "Exit code: 1 (input-only probe)\nInput #0, mov, from 'episode.mp4':\nStream #0:0: Video: h264");

        assertTrue(report.startsWith("Failed to instantiate extractor.\n"));
        assertTrue(report.contains("Exception: java.io.IOException"));
        assertTrue(report.contains("Stage: download completion validation"));
        assertTrue(report.contains("Source URL: " + url));
        assertTrue(report.contains("Source MIME (not the final container): application/x-mpegURL"));
        assertTrue(report.contains("Final file: " + file.getAbsolutePath()));
        assertTrue(report.contains("File size: 24 bytes"));
        assertTrue(report.contains("Detected container MIME (header sniff): video/mp4"));
        assertTrue(report.contains("Device / app: Test device / Android 10 / API 29"));
        assertTrue(report.contains("read-only, not a completeness guarantee"));
        assertTrue(report.contains("Stream #0:0: Video: h264"));
        assertTrue(report.contains(CacheCompletionDiagnostics.TAG));
        assertEquals(24, file.length());
    }

    @Test
    public void detectsTsEvenWhenFilenameSaysMp4() throws Exception {
        byte[] packets = new byte[188 * 5];
        for (int packet = 0; packet < 5; packet++) {
            packets[packet * 188] = 0x47;
            packets[packet * 188 + 3] = 0x10;
        }
        File file = temporary.newFile("misleading.mp4");
        Files.write(file.toPath(), packets);
        String report = describe(new IOException("Failed to instantiate extractor."), file, null, media -> "mpegts");
        assertTrue(report.contains("Detected container MIME (header sniff): video/mp2t"));
        assertTrue(report.contains("File size: 940 bytes"));
    }

    @Test
    public void neverInfersUnknownFormatFromExtensionAndRetainsProbeEvidence() throws Exception {
        File file = temporary.newFile("broken.mp4");
        Files.write(file.toPath(), new byte[]{1, 2, 3, 4});
        String report = describe(new IOException("Failed to instantiate extractor."), file, null,
                media -> "Invalid data found when processing input");
        assertTrue(report.contains("unknown; not inferred from filename"));
        assertTrue(report.contains("First 32 bytes (hex): 01 02 03 04"));
        assertTrue(report.contains("Invalid data found when processing input"));
        assertFalse(report.contains("header sniff): video/mp4"));
    }

    @Test
    public void skipsProbeForUnverifiedPathButKeepsAttemptedPath() {
        AtomicInteger calls = new AtomicInteger();
        String report = CacheCompletionDiagnostics.describe(new SecurityException("Path outside cache directory"),
                null, "/outside/cache/private.mp4", null, null, null, null,
                media -> { calls.incrementAndGet(); return "unexpected"; });
        assertEquals(0, calls.get());
        assertTrue(report.contains("Final file: /outside/cache/private.mp4"));
        assertTrue(report.contains("no verified path inside the cache directory"));
        assertTrue(report.contains("skipped; no verified cache file"));
    }

    @Test
    public void skipsMissingAndEmptyFileWithoutMaskingOriginalError() throws Exception {
        File empty = temporary.newFile("empty.ts");
        File missing = new File(temporary.getRoot(), "missing.ts");
        AtomicInteger calls = new AtomicInteger();
        CacheCompletionDiagnostics.Probe probe = media -> { calls.incrementAndGet(); return "unexpected"; };
        for (File file : new File[]{empty, missing}) {
            String report = describe(new IOException("Cache file not found"), file, null, probe);
            assertTrue(report.startsWith("Cache file not found\n"));
            assertTrue(report.contains("File size: 0 bytes"));
            assertTrue(report.contains("skipped; file is missing, unreadable, or empty"));
        }
        assertEquals(0, calls.get());
    }

    @Test
    public void probeFailureRetainsOriginalErrorAndFileEvidence() throws Exception {
        File file = temporary.newFile("episode.ts");
        Files.write(file.toPath(), new byte[]{1});
        String report = describe(new IOException("Failed to instantiate extractor."), file, null,
                media -> { throw new IllegalStateException("executable unavailable"); });
        assertTrue(report.startsWith("Failed to instantiate extractor.\n"));
        assertTrue(report.contains("File size: 1 bytes"));
        assertTrue(report.contains("unavailable: java.lang.IllegalStateException: executable unavailable"));
    }

    @Test
    public void includesCauseAndOfflineWarningWithoutChangingPrimaryMessage() throws Exception {
        IOException error = new IOException("Unable to inspect cached media tracks", new IllegalArgumentException("bad data source"));
        String report = CacheCompletionDiagnostics.describe(error, null, null, null, null,
                "separate audio could not be merged", "Test device", null);
        assertTrue(report.startsWith("Unable to inspect cached media tracks\n"));
        assertTrue(report.contains("Cause: java.lang.IllegalArgumentException: bad data source"));
        assertTrue(report.contains("FluxDown warning: separate audio could not be merged"));
    }

    @Test
    public void handlesErrorWithoutMessage() {
        String report = describe(new IOException(), null, null, null);
        assertTrue(report.startsWith("Cached file is missing, unreadable, or incomplete\n"));
        assertTrue(report.contains("Final file: unknown"));
    }

    private static String describe(Exception error, File file, String url, CacheCompletionDiagnostics.Probe probe) {
        return CacheCompletionDiagnostics.describe(error, file, file == null ? null : file.getAbsolutePath(),
                url, "application/x-mpegURL", null, "Test device / Android 10 / API 29", probe);
    }

    private static byte[] mp4Header() {
        return new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
                0, 0, 0, 0, 'i', 's', 'o', 'm', 'a', 'v', 'c', '1'};
    }
}
