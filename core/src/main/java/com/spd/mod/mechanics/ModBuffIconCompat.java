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

    /**
     * Resolve the first icon field that actually exists on the target fork.
     * This is for semantic icons introduced late in SPD history: modern targets
     * keep their intended icon, while older targets fall back to a real field
     * from their own BuffIndicator instead of guessing an atlas slot.
     *
     * If release shrinking removed every candidate field name, use the known
     * fallback for the last (most conservative) candidate.
     */
    public static synchronized int getFirst(String... fieldNames) {
        if (fieldNames == null || fieldNames.length == 0) {
            return knownFallback("NONE");
        }

        StringBuilder keyBuilder = new StringBuilder("first:");
        for (String fieldName : fieldNames) {
            keyBuilder.append(fieldName).append('|');
        }
        String key = keyBuilder.toString();

        Integer cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        for (String fieldName : fieldNames) {
            int value = read(fieldName);
            if (value != Integer.MIN_VALUE) {
                CACHE.put(key, value);
                return value;
            }
        }

        int value = knownFallback(fieldNames[fieldNames.length - 1]);
        CACHE.put(key, value);
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
     * referenced through reflection. Keep conservative semantic fallbacks for
     * release builds and old forks. AMULET deliberately falls back to HEART:
     * older buff atlases do not contain slot 59, while HEART's low slot is
     * available across the supported SPD lineage and is suitable for Last Stand.
     */
    private static int knownFallback(String fieldName) {
        switch (fieldName) {
            case "HEART":
                return 21;
            case "MARK":
                return 27;
            case "RAGE":
                return 38;
            case "PREPARATION":
                return 42;
            case "INVERT_MARK":
                return 57;
            case "AMULET":
                return 21;
            case "DUEL_CLEAVE":
                // Older buff atlases predate Duelist slots entirely. MARK is
                // a stable low-slot combat icon and remains visible there.
                return 27;
            case "DUEL_GUARD":
                return 61;
            case "NONE":
            default:
                return 127;
        }
    }
}
