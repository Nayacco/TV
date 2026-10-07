package com.fongmi.android.tv.setting;

/**
 * Compatibility facade for data models that still expose optional danmaku metadata.
 * The minimal official Media3 player does not render or fetch danmaku.
 */
public final class DanmakuSetting {

    private DanmakuSetting() {
    }

    public static boolean isLoad() {
        return false;
    }

    public static boolean isAuto() {
        return false;
    }

    public static String getEffectiveApiUrl() {
        return "";
    }
}
