package com.fongmi.android.tv.player.exo;

import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;

import java.util.Collections;
import java.util.Map;

/** Owns the official Media3 data-source stack used by the single ExoPlayer instance. */
final class ExoMediaSourceFactory {

    private final OkHttpDataSource.Factory httpFactory;
    private final DefaultMediaSourceFactory mediaSourceFactory;

    ExoMediaSourceFactory() {
        httpFactory = new OkHttpDataSource.Factory(OkHttp.player());
        mediaSourceFactory = new DefaultMediaSourceFactory(new DefaultDataSource.Factory(App.get(), httpFactory));
    }

    MediaSource.Factory get() {
        return mediaSourceFactory;
    }

    void setRequestHeaders(Map<String, String> headers) {
        httpFactory.setDefaultRequestProperties(headers == null ? Collections.emptyMap() : headers);
    }
}
