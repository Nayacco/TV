package com.fongmi.android.tv.player;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.engine.PlayerEngine.SecondarySubtitleState;
import com.fongmi.android.tv.player.effect.audio.AudioEffectBands;
import com.fongmi.android.tv.player.engine.PlayerEngineFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.parse.ParseJob;
import com.fongmi.android.tv.player.track.TrackUtil;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.SpeedSetting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.common.net.HttpHeaders;

import java.util.HashMap;
import java.util.Map;

/**
 * Coordinates the app's playback lifecycle around one official Media3 ExoPlayer.
 *
 * <p>Persistent downloads are handled by FluxDown. This class intentionally has no player-side
 * disk preloader, alternate engine, native subtitle renderer, danmaku pipeline or media effects.
 */
public class PlayerManager implements ParseCallback {

    private final Runnable timeoutRunnable;
    private final Callback callback;
    private PlayerEngine engine;
    private VideoSize videoSize;
    private ParseJob parseJob;
    private PlaySpec spec;
    private Player player;
    private long pendingStartPositionMs;
    private boolean initTrack;

    public PlayerManager(Callback callback) {
        this.callback = callback;
        timeoutRunnable = this::onPlayTimeout;
        pendingStartPositionMs = C.TIME_UNSET;
        engine = PlayerEngineFactory.create(PlayerEngine.HARD, listener);
        player = engine.getPlayer();
    }

    public void release() {
        App.removeCallbacks(timeoutRunnable);
        stopParse();
        if (player != null) player.removeListener(listener);
        if (engine != null) engine.release();
        engine = null;
        player = null;
    }

    public Player getPlayer() {
        return player;
    }

    public Tracks getCurrentTracks() {
        return player == null ? Tracks.EMPTY : player.getCurrentTracks();
    }

    public int getAudioChannelCount() {
        return engine == null ? Format.NO_VALUE : engine.getAudioChannelCount();
    }

    public MediaItem getCurrentMediaItem() {
        return player == null ? null : player.getCurrentMediaItem();
    }

    public int getPlaybackState() {
        return player == null ? Player.STATE_IDLE : player.getPlaybackState();
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public boolean isReleased() {
        return player == null;
    }

    public String getUrl() {
        return spec == null ? null : spec.getUrl();
    }

    public String getKey() {
        return spec == null ? null : spec.getKey();
    }

    public MediaMetadata getMetadata() {
        return spec == null ? null : spec.getMetadata();
    }

    public String getMediaTitle() {
        MediaMetadata metadata = getMetadata();
        if (metadata == null) return "";
        CharSequence title = !TextUtils.isEmpty(metadata.displayTitle) ? metadata.displayTitle : metadata.title;
        if (TextUtils.isEmpty(title)) title = metadata.artist;
        return TextUtils.isEmpty(title) ? "" : title.toString();
    }

    public void setMetadata(@NonNull MediaMetadata metadata) {
        if (spec == null || metadata.equals(spec.getMetadata())) return;
        spec.setMetadata(metadata);
        if (player == null || TextUtils.isEmpty(spec.getUrl())) return;
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(), current.buildUpon().setMediaMetadata(metadata).build());
    }

    public Map<String, String> getHeaders() {
        return spec == null || spec.getHeaders() == null ? new HashMap<>() : spec.getHeaders();
    }

    public float getSpeed() {
        return player == null ? 1f : player.getPlaybackParameters().speed;
    }

    public boolean isEmpty() {
        return spec == null || TextUtils.isEmpty(spec.getUrl());
    }

    public boolean hasPlaySpec() {
        return spec != null;
    }

    public boolean isPortrait() {
        return getVideoHeight() > getVideoWidth();
    }

    public boolean isLandscape() {
        return getVideoWidth() > getVideoHeight();
    }

    public boolean isLive() {
        return player != null && player.getCurrentMediaItem() != null && player.isCurrentMediaItemLive();
    }

    public boolean isVod() {
        return player != null && player.getCurrentMediaItem() != null && !player.isCurrentMediaItemLive();
    }

    public boolean haveTrack(int type) {
        return TrackUtil.count(getCurrentTracks(), type) > 0;
    }

    public boolean haveEdition() {
        return false;
    }

    public boolean haveChapter() {
        return false;
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public int getVideoWidth() {
        return videoSize == null ? 0 : videoSize.width;
    }

    public int getVideoHeight() {
        return videoSize == null ? 0 : videoSize.height;
    }

    public long getPosition() {
        return player == null ? 0 : player.getCurrentPosition();
    }

    public String getSizeText() {
        return getVideoWidth() == 0 && getVideoHeight() == 0 ? "" : getVideoWidth() + " x " + getVideoHeight();
    }

    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[PlayerEngine.HARD];
    }

    public int getEngine() {
        return PlayerSetting.ENGINE_EXO;
    }

    public void setEngine(int targetEngine) {
        PlayerSetting.putEngine(PlayerSetting.ENGINE_EXO);
    }

    public boolean canSetAudioSetting() {
        return false;
    }

    public AudioEffectBands getAudioSettingBands() {
        return AudioEffectBands.EMPTY;
    }

    public int getAudioSettingError() {
        return R.string.error_audio_effect_unsupported;
    }

    public void setAudioSetting(int preset) {
    }

    public void refreshAudioSetting() {
    }

    public void previewAudioSetting(boolean original) {
    }

    public boolean canSetVideoSetting() {
        return false;
    }

    public int getVideoSettingError() {
        return R.string.error_video_effect_unsupported;
    }

    public boolean supportsVideoSharpness() {
        return false;
    }

    public void setVideoSetting(int preset) {
    }

    public void refreshVideoSetting() {
    }

    public void previewVideoSetting(boolean original) {
    }

    public String getPositionTime(long delta) {
        return Util.timeMs(Math.clamp(getPosition() + delta, 0, Math.max(0, getDuration())));
    }

    public long getDuration() {
        return player == null ? C.TIME_UNSET : player.getDuration();
    }

    public String getDurationTime() {
        return Util.timeMs(Math.max(0, getDuration()));
    }

    public void setSub(Sub sub) {
        if (sub == null || sub.isEmpty() || spec == null) return;
        spec.setSub(sub);
        startCurrent();
    }

    public float setSpeed(float speed) {
        if (player == null || !player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return getSpeed();
        player.setPlaybackSpeed(SpeedSetting.clamp(speed));
        return getSpeed();
    }

    public float toggleSpeed() {
        return setSpeed(getSpeed() == 1 ? SpeedSetting.getLongPress() : 1);
    }

    public boolean supportsSkipSilence() {
        return player instanceof ExoPlayer;
    }

    public boolean isSkipSilence() {
        return SpeedSetting.isSkipSilence();
    }

    public void setSkipSilenceEnabled(boolean enabled) {
        SpeedSetting.putSkipSilence(enabled);
        if (player instanceof ExoPlayer exoPlayer) exoPlayer.setSkipSilenceEnabled(enabled);
    }

    public void setTrack(Track track) {
        if (player != null) TrackUtil.setTrackSelection(player, track);
    }

    public void play() {
        if (player != null) player.play();
    }

    public void pause() {
        if (player != null) player.pause();
    }

    public void stop() {
        App.removeCallbacks(timeoutRunnable);
        if (engine != null) engine.stop();
        stopParse();
    }

    public void clearMediaItems() {
        if (player != null) player.clearMediaItems();
    }

    public boolean isRepeatOne() {
        return player != null && player.getRepeatMode() == Player.REPEAT_MODE_ONE;
    }

    public void setRepeatOne(boolean repeat) {
        if (player != null) player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    public void replay(long positionMs) {
        if (player == null) return;
        if (positionMs == C.TIME_UNSET) player.seekToDefaultPosition();
        else player.seekTo(positionMs);
        player.play();
    }

    public void seekTo(long time) {
        if (player != null) player.seekTo(time);
    }

    /** Official Media3 has no per-player subtitle offset command. */
    public long getTextOffsetMs() {
        return 0;
    }

    public void setTextOffsetMs(long offsetMs) {
    }

    /** Official Media3 has no per-player audio offset command. */
    public long getAudioOffsetMs() {
        return 0;
    }

    public void setAudioOffsetMs(long offsetMs) {
    }

    public void reset() {
        App.removeCallbacks(timeoutRunnable);
    }

    public void clear() {
        spec = null;
    }

    public boolean canPreloadNext() {
        return false;
    }

    public boolean preload(PlaySpec spec, long startPositionMs) {
        return false;
    }

    public void clearPreload() {
    }

    public void bindPlayerView(PlayerView playerView) {
        if (engine != null) engine.bindPlayerView(playerView);
    }

    public void resetTrack() {
        if (player != null) TrackUtil.reset(player);
    }

    public void toggleDecode() {
        callback.onDecodeChanged();
    }

    public void applySubtitleStyle() {
    }

    public SecondarySubtitleState getSecondarySubtitleState() {
        return SecondarySubtitleState.EMPTY;
    }

    public void setSecondarySubtitleSelection(@Nullable TrackSelectionOverride selection) {
    }

    private void onPlayTimeout() {
        callback.onError(ResUtil.getString(R.string.error_play_timeout));
        stop();
    }

    public void browse(PlaySpec spec, long startPositionMs) {
        reset();
        clear();
        stopParse();
        start(spec, Constant.TIMEOUT_PLAY, startPositionMs);
    }

    public void start(PlaySpec spec, long timeout) {
        start(spec, timeout, C.TIME_UNSET);
    }

    public void start(PlaySpec spec, long timeout, long startPositionMs) {
        this.spec = spec;
        setMediaItem(timeout, startPositionMs);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata) {
        parse(key, result, useParse, metadata, C.TIME_UNSET);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, long startPositionMs) {
        stopParse();
        pendingStartPositionMs = startPositionMs;
        spec = PlaySpec.fromParse(result, key, metadata);
        parseJob = ParseJob.create(this).start(result, useParse);
    }

    private void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
        pendingStartPositionMs = C.TIME_UNSET;
    }

    private void setMediaItem(long timeout, long startPositionMs) {
        if (spec == null || spec.getUrl() == null || engine == null) return;
        initTrack = false;
        engine.start(spec.checkUa(), startPositionMs);
        App.post(timeoutRunnable, timeout);
        callback.onPrepare();
    }

    private void startCurrent() {
        startCurrent(getPosition());
    }

    private void startCurrent(long startPositionMs) {
        setMediaItem(Constant.TIMEOUT_PLAY, startPositionMs);
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from));
        if (headers != null) headers.remove(HttpHeaders.RANGE);
        if (spec != null) spec.setHeaders(headers);
        if (spec != null) spec.setUrl(url);
        startCurrent(pendingStartPositionMs);
        pendingStartPositionMs = C.TIME_UNSET;
    }

    @Override
    public void onParseError() {
        pendingStartPositionMs = C.TIME_UNSET;
        callback.onError(ResUtil.getString(R.string.error_play_parse));
    }

    public interface Callback {

        void onPrepare();

        void onTracksChanged();

        void onDecodeChanged();

        void onMediaOptionsChanged();

        void onError(String msg);

        void onPlayerRebuild(Player newPlayer);
    }

    private final Player.Listener listener = new Player.Listener() {

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) App.removeCallbacks(timeoutRunnable);
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize size) {
            videoSize = size;
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if (tracks.isEmpty() || initTrack) return;
            initTrack = true;
            TrackUtil.setTrackSelection(player, Track.find(getKey()));
            callback.onTracksChanged();
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            if (spec == null || engine == null) return;
            PlayerEngine.ErrorAction action = engine.handleError(error);
            if (action == PlayerEngine.ErrorAction.RECOVERED) return;
            App.removeCallbacks(timeoutRunnable);
            callback.onError(engine.getErrorMessage(error));
        }
    };
}
