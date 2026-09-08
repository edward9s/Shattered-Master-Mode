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
        if (value == Integer.MIN_VALUE && !"NONE".equals(fieldName)) {
            value = read("NONE");
        }
        if (value == Integer.MIN_VALUE) {
            value = BuffIndicator.NONE;
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
}
