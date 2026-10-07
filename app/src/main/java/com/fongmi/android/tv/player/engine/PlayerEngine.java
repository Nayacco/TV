package com.fongmi.android.tv.player.engine;

import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.player.media.PlaySpec;

import java.util.List;

public interface PlayerEngine {

    int HARD = 0;
    int SOFT = 1;

    Type getType();

    Player getPlayer();

    int getAudioChannelCount();

    void release();

    void start(PlaySpec spec, long startPositionMs);

    default void bindPlayerView(PlayerView playerView) {
    }

    default SecondarySubtitleState getSecondarySubtitleState() {
        return SecondarySubtitleState.EMPTY;
    }

    default void setSecondarySubtitleSelection(@Nullable TrackSelectionOverride selection) {
    }

    void stop();

    String getErrorMessage(PlaybackException error);

    ErrorAction handleError(PlaybackException error);

    enum ErrorAction {
        RECOVERED,
        FATAL
    }

    enum Type {
        EXO
    }

    record SecondarySubtitleState(@Nullable TrackSelectionOverride primarySelection,
                                  @Nullable TrackSelectionOverride explicitSelection,
                                  List<TrackSelectionOverride> secondaryCandidates,
                                  boolean secondaryPromotedToPrimary) {

        public static final SecondarySubtitleState EMPTY = new SecondarySubtitleState(null, null, List.of(), false);

        public SecondarySubtitleState {
            secondaryCandidates = List.copyOf(secondaryCandidates);
        }
    }
}
