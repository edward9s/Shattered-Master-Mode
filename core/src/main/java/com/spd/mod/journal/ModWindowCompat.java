package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.watabou.noosa.ui.Component;

import java.lang.reflect.Method;

/** Compatibility helpers for WndTitledMessage variants across SPD forks. */
final class ModWindowCompat {

    private ModWindowCompat() {
    }

    /**
     * Newer forks keep WndTitledMessage content in a ScrollPane and require
     * addToBottom() so controls reserve space instead of covering the message.
     * Older forks do not have that API and keep using the legacy manual layout.
     */
    static boolean addToBottom(WndInfoBuff window, Component controls, int gapBefore, int gapAfter) {
        try {
            Method method = window.getClass().getMethod(
                    "addToBottom", Component.class, int.class, int.class);
            method.invoke(window, controls, gapBefore, gapAfter);
            return true;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return false;
        }
    }
}
