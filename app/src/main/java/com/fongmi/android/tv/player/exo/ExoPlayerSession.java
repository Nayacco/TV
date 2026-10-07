package com.fongmi.android.tv.player.exo;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;

/** A small lifecycle boundary around one official AndroidX Media3 player. */
final class ExoPlayerSession {

    private final ExoMediaSourceFactory mediaSources;
    private final ExoPlayer player;

    ExoPlayerSession(Player.Listener listener) {
        mediaSources = new ExoMediaSourceFactory();
        player = ExoUtil.buildPlayer(listener, mediaSources.get());
    }

    ExoPlayer player() {
        return player;
    }

    void start(PlaySpec spec, long startPositionMs) {
        mediaSources.setRequestHeaders(spec.getHeaders());
        player.setMediaItem(MediaItemFactory.from(spec), startPositionMs);
        player.prepare();
        player.play();
    }

    void release() {
        player.release();
    }
}
