package com.fongmi.android.tv.cache;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Local-only failure evidence; never changes the result of completion validation. */
final class CacheCompletionDiagnostics {

    static final String TAG = "[DEBUG-cache-completion-a91f]";

    interface Probe {
        String inspect(File file);
    }

    private CacheCompletionDiagnostics() {
    }

    /** ownedFile must have passed CachePaths.requireOwned; an untrusted path is display-only. */
    static String describe(Exception error, File ownedFile, String attemptedPath,
                           String sourceUrl, String sourceMimeType, String offlineWarning,
                           String device, Probe probe) {
        String message = error == null ? null : error.getMessage();
        StringBuilder report = new StringBuilder(value(message, "Cached file is missing, unreadable, or incomplete"));
        report.append('\n').append(TAG);
        append(report, "Stage", "download completion validation");
        append(report, "Exception", error == null ? "unknown" : error.getClass().getName());
        Throwable cause = error == null ? null : error.getCause();
        for (int depth = 0; cause != null && depth < 4; depth++) {
            append(report, "Cause", cause.getClass().getName() + ": " + value(cause.getMessage(), "no message"));
            cause = cause.getCause();
        }
        append(report, "Device / app", value(device, "unknown"));
        append(report, "Source URL", value(sourceUrl, "unknown"));
        append(report, "Source MIME (not the final container)", value(sourceMimeType, "unknown"));
        append(report, "Final file", value(attemptedPath, ownedFile == null ? "unknown" : ownedFile.getAbsolutePath()));
        if (offlineWarning != null && !offlineWarning.isEmpty()) append(report, "FluxDown warning", offlineWarning);

        if (ownedFile == null) {
            append(report, "File inspection", "unavailable (no verified path inside the cache directory)");
            append(report, "FFmpeg probe", "skipped; no verified cache file");
            return report.toString();
        }

        try {
            boolean exists = ownedFile.exists();
            boolean regular = ownedFile.isFile();
            boolean readable = regular && ownedFile.canRead();
            long size = ownedFile.length();
            append(report, "File state", "exists=" + exists + ", regular=" + regular + ", readable=" + readable);
            append(report, "File size", size + " bytes");
            if (!readable || size <= 0) {
                append(report, "Detected container MIME", "unknown; file is missing, unreadable, or empty");
                append(report, "FFmpeg probe", "skipped; file is missing, unreadable, or empty");
                return report.toString();
            }

            try {
                String mime = CacheFileValidator.mediaMimeType(ownedFile);
                if (mime == null) mime = CacheFileValidator.streamingMimeType(ownedFile);
                append(report, "Detected container MIME (header sniff)", value(mime, "unknown; not inferred from filename"));
                append(report, "First 32 bytes (hex)", headerHex(ownedFile));
            } catch (IOException | RuntimeException inspectionError) {
                append(report, "Container inspection error", inspectionError.toString());
            }

            try {
                append(report, "FFmpeg probe (read-only, not a completeness guarantee)",
                        probe == null ? "unavailable" : value(probe.inspect(ownedFile), "no diagnostic output"));
            } catch (RuntimeException probeError) {
                append(report, "FFmpeg probe", "unavailable: " + probeError);
            }
        } catch (RuntimeException inspectionError) {
            append(report, "File inspection error", inspectionError.toString());
        }
        return report.toString();
    }

    private static String headerHex(File file) throws IOException {
        StringBuilder hex = new StringBuilder();
        try (FileInputStream input = new FileInputStream(file)) {
            for (int index = 0; index < 32; index++) {
                int next = input.read();
                if (next < 0) break;
                if (index > 0) hex.append(' ');
                hex.append(Character.forDigit(next >>> 4, 16)).append(Character.forDigit(next & 15, 16));
            }
        }
        return hex.toString();
    }

    private static void append(StringBuilder report, String name, String value) {
        report.append('\n').append(name).append(": ").append(value);
    }

    private static String value(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
