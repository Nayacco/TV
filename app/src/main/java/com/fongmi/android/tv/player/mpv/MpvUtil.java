package com.fongmi.android.tv.player.mpv;

import java.util.List;

/** Compatibility surface for settings code while the MPV engine is no longer shipped. */
public final class MpvUtil {

    private MpvUtil() {
    }

    public static boolean isAvailable() {
        return false;
    }

    public static boolean isVulkanSupported() {
        return false;
    }

    static List<String> getManagedOptionNames() {
        return List.of();
    }
}
