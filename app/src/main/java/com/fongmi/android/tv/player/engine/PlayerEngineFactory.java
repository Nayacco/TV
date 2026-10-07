package com.fongmi.android.tv.player.engine;

import androidx.media3.common.Player;

import com.fongmi.android.tv.player.exo.ExoPlayerEngine;
import com.fongmi.android.tv.player.media.PlaySpec;

public final class PlayerEngineFactory {

    private PlayerEngineFactory() {
    }

    public static PlayerEngine create(int decode, Player.Listener listener) {
        return new ExoPlayerEngine(listener);
    }

    public static PlayerEngine create(int decode, PlaySpec spec, Player.Listener listener) {
        return new ExoPlayerEngine(listener);
    }

    public static boolean matches(PlayerEngine engine, PlaySpec spec) {
        return engine != null && engine.getType() == PlayerEngine.Type.EXO;
    }
}
