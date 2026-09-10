package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/** Resolves BuffIndicator icon IDs from the target game at runtime. */
public final class ModBuffIconCompat {

    private static final Map<String, Integer> CACHE = new HashMap<>();

    private ModBuffIconCompat() {
    }

    public static synchronized int get(String fieldName) {
        Integer cached = CACHE.get(fieldName);
        if (cached != null) {
            return cached;
        }

        int value = read(fieldName);
        if (value == Integer.MIN_VALUE) {
            value = knownFallback(fieldName);
        }

        CACHE.put(fieldName, value);
        return value;
    }

    private static int read(String fieldName) {
        try {
            Field field = BuffIndicator.class.getField(fieldName);
            return field.getInt(null);
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * R8 may rename or remove public static final fields that are otherwise only
     * referenced through reflection. These semantic IDs are shared by the SPD
     * forks supported by SMM, so keep a non-reflective fallback for release builds.
     */
    private static int knownFallback(String fieldName) {
        switch (fieldName) {
            case "MARK":
                return 27;
            case "RAGE":
                return 38;
            case "PREPARATION":
                return 42;
            case "INVERT_MARK":
                return 57;
            case "AMULET":
                return 59;
            case "DUEL_CLEAVE":
                return 60;
            case "DUEL_GUARD":
                return 61;
            case "NONE":
            default:
                return 127;
        }
    }
}
