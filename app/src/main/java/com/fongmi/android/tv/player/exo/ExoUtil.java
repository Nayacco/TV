package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.track.LangUtil;
import com.fongmi.android.tv.setting.SpeedSetting;

final class ExoUtil {

    private ExoUtil() {
    }

    static ExoPlayer buildPlayer(Player.Listener listener, MediaSource.Factory mediaSourceFactory) {
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(App.get());
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setPreferredTextLanguages(LangUtil.getPreferredTextLanguages())
                .build());
        ExoPlayer player = new ExoPlayer.Builder(App.get())
                .setMediaSourceFactory(mediaSourceFactory)
                .setTrackSelector(trackSelector)
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .setHandleAudioBecomingNoisy(true)
                .setSkipSilenceEnabled(SpeedSetting.isSkipSilence())
                .build();
        player.setPlayWhenReady(true);
        player.addListener(listener);
        return player;
    }

    @Nullable
    static String getFallbackMimeType(int errorCode) {
        return switch (errorCode) {
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                 PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                 PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> MimeTypes.APPLICATION_M3U8;
            default -> null;
        };
    }
}
