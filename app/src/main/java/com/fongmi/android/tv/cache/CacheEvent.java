package com.fongmi.android.tv.cache;

import org.greenrobot.eventbus.EventBus;

public final class CacheEvent {

    private CacheEvent() {
    }

    public static void refresh() {
        EventBus.getDefault().post(new CacheEvent());
    }
}
