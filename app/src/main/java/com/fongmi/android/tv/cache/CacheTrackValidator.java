package com.fongmi.android.tv.cache;

import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;

/** Verifies that adaptive-stream output kept both tracks required for offline video playback. */
public final class CacheTrackValidator {

    public static final String MISSING_AUDIO_MESSAGE = "Cached adaptive stream has no audio track; offline cache is incomplete";
    public static final String MISSING_VIDEO_MESSAGE = "Cached adaptive stream has no video track; offline cache is incomplete";

    private CacheTrackValidator() {
    }

    public static File requireCompleteTracks(File file, String sourceUrl, String sourceMimeType) throws IOException {
        if (!requiresTrackInspection(file, sourceUrl, sourceMimeType)) return file;
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            boolean audio = false;
            boolean video = false;
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                MediaFormat format = extractor.getTrackFormat(index);
                String mimeType = format.getString(MediaFormat.KEY_MIME);
                audio |= mimeType != null && mimeType.startsWith("audio/");
                video |= mimeType != null && mimeType.startsWith("video/");
            }
            requireVideoAudioTracks(video, audio);
            return file;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Unable to inspect cached media tracks", e);
        } finally {
            extractor.release();
        }
    }

    static void requireVideoAudioTracks(boolean video, boolean audio) throws IOException {
        if (!video) throw new IOException(MISSING_VIDEO_MESSAGE);
        if (!audio) throw new IOException(MISSING_AUDIO_MESSAGE);
    }

    private static boolean requiresTrackInspection(File file, String sourceUrl, String sourceMimeType) throws IOException {
        if (CacheMediaUrl.isAdaptiveStream(sourceUrl, sourceMimeType)) return true;
        return file != null && (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".ts")
                || CacheFileValidator.TS_MIME_TYPE.equals(CacheFileValidator.mediaMimeType(file)));
    }
}
