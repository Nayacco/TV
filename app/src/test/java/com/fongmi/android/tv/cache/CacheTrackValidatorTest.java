package com.fongmi.android.tv.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;

public class CacheTrackValidatorTest {

    @Test
    public void acceptsBothVideoAndAudio() throws Exception {
        CacheTrackValidator.requireVideoAudioTracks(true, true);
    }

    @Test
    public void rejectsVideoWithoutAudio() {
        IOException error = assertThrows(IOException.class,
                () -> CacheTrackValidator.requireVideoAudioTracks(true, false));
        assertEquals(CacheTrackValidator.MISSING_AUDIO_MESSAGE, error.getMessage());
    }

    @Test
    public void rejectsAudioWithoutVideo() {
        IOException error = assertThrows(IOException.class,
                () -> CacheTrackValidator.requireVideoAudioTracks(false, true));
        assertEquals(CacheTrackValidator.MISSING_VIDEO_MESSAGE, error.getMessage());
    }

    @Test
    public void rejectsFileWithoutEitherTrack() {
        IOException error = assertThrows(IOException.class,
                () -> CacheTrackValidator.requireVideoAudioTracks(false, false));
        assertEquals(CacheTrackValidator.MISSING_VIDEO_MESSAGE, error.getMessage());
    }
}
