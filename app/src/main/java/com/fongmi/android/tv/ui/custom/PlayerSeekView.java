package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.R;
import androidx.media3.ui.TimeBar;

import java.util.Locale;

/**
 * Small seek-only controller used by the existing playback layouts.
 *
 * <p>The upstream Media3 UI artifact does not provide the custom PlayerSeekView that the app
 * previously received from its bundled AARs. This replacement intentionally exposes only the
 * contract used by the app: {@link #setPlayer(Player)}, {@link #getTimeBar()}, and a time bar with
 * the standard {@link R.id#exo_progress} id.</p>
 */
@UnstableApi
public final class PlayerSeekView extends LinearLayout {

    private static final long PROGRESS_UPDATE_INTERVAL_MS = 1_000;

    private final Handler handler;
    private final TextView positionView;
    private final TextView durationView;
    private final DefaultTimeBar timeBar;
    private final Runnable updateProgressAction;
    private final Player.Listener playerListener;

    @Nullable
    private Player player;
    private boolean scrubbing;

    public PlayerSeekView(Context context) {
        this(context, null);
    }

    public PlayerSeekView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PlayerSeekView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        handler = new Handler(Looper.getMainLooper());
        updateProgressAction = this::updateProgress;
        playerListener = new Player.Listener() {
            @Override
            public void onEvents(Player player, Player.Events events) {
                updateProgress();
            }
        };

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBaselineAligned(false);

        positionView = createTimeView(context);
        durationView = createTimeView(context);
        timeBar = new DefaultTimeBar(context);
        timeBar.setId(R.id.exo_progress);
        timeBar.setFocusable(true);
        timeBar.addListener(new TimeBar.OnScrubListener() {
            @Override
            public void onScrubStart(TimeBar timeBar, long position) {
                scrubbing = true;
                positionView.setText(formatTime(position));
            }

            @Override
            public void onScrubMove(TimeBar timeBar, long position) {
                positionView.setText(formatTime(position));
            }

            @Override
            public void onScrubStop(TimeBar timeBar, long position, boolean canceled) {
                scrubbing = false;
                Player currentPlayer = player;
                if (!canceled && currentPlayer != null
                        && currentPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
                    currentPlayer.seekTo(position);
                }
                updateProgress();
            }
        });

        addView(positionView, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        addView(timeBar, new LayoutParams(0, dpToPx(40), 1f));
        addView(durationView, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        updateProgress();
    }

    public void setPlayer(@Nullable Player player) {
        if (this.player == player) return;
        if (this.player != null) this.player.removeListener(playerListener);
        this.player = player;
        if (player != null) player.addListener(playerListener);
        scrubbing = false;
        updateProgress();
    }

    public TimeBar getTimeBar() {
        return timeBar;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateProgress();
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(updateProgressAction);
        super.onDetachedFromWindow();
    }

    private void updateProgress() {
        handler.removeCallbacks(updateProgressAction);

        Player currentPlayer = player;
        long duration = currentPlayer == null ? 0 : currentPlayer.getDuration();
        if (duration == C.TIME_UNSET || duration < 0) duration = 0;
        long position = currentPlayer == null ? 0 : Math.max(0, currentPlayer.getCurrentPosition());
        long bufferedPosition = currentPlayer == null ? 0 : Math.max(0, currentPlayer.getBufferedPosition());

        timeBar.setDuration(duration);
        timeBar.setPosition(position);
        timeBar.setBufferedPosition(bufferedPosition);
        boolean canSeek = currentPlayer != null && duration > 0
                && currentPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM);
        timeBar.setEnabled(canSeek);
        if (!scrubbing) positionView.setText(formatTime(position));
        durationView.setText(formatTime(duration));

        if (isAttachedToWindow() && currentPlayer != null && currentPlayer.isPlaying()) {
            handler.postDelayed(updateProgressAction, PROGRESS_UPDATE_INTERVAL_MS);
        }
    }

    private TextView createTimeView(Context context) {
        TextView view = new TextView(context);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(dpToPx(54));
        return view;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private static String formatTime(long timeMs) {
        long totalSeconds = Math.max(0, timeMs) / 1_000;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3_600;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}
