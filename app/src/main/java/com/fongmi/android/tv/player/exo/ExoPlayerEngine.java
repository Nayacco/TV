package com.fongmi.android.tv.player.exo;

import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.PlaySpec;

public final class ExoPlayerEngine implements PlayerEngine {

    private final ExoErrorMessageProvider errorMessageProvider;
    private final ExoPlayerSession session;
    private final ExoPlayer player;
    private PlaySpec spec;

    public ExoPlayerEngine(Player.Listener listener) {
        errorMessageProvider = new ExoErrorMessageProvider();
        session = new ExoPlayerSession(listener);
        player = session.player();
    }

    @Override
    public Type getType() {
        return Type.EXO;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public int getAudioChannelCount() {
        Format format = player.getAudioFormat();
        return format == null ? Format.NO_VALUE : format.channelCount;
    }

    @Override
    public void release() {
        session.release();
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs) {
        this.spec = spec;
        session.start(spec, startPositionMs);
    }

    @Override
    public void stop() {
        player.stop();
    }

    @Override
    public String getErrorMessage(PlaybackException error) {
        return errorMessageProvider.get(error);
    }

    @Override
    public ErrorAction handleError(PlaybackException error) {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition();
            player.prepare();
            return ErrorAction.RECOVERED;
        }
        String fallbackMimeType = ExoUtil.getFallbackMimeType(error.errorCode);
        if (fallbackMimeType != null && spec != null && !fallbackMimeType.equals(spec.getFormat())) {
            spec.setFormat(fallbackMimeType);
            session.start(spec, player.getCurrentPosition());
            return ErrorAction.RECOVERED;
        }
        return ErrorAction.FATAL;
    }
}
