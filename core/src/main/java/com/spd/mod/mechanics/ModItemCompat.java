package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.items.Item;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Runtime adapters for small Item ABI differences between SPD-family targets. */
public final class ModItemCompat {

    private static Method levelSetter;

    private ModItemCompat() {
    }

    /**
     * Sets Item level while tolerating the ABI change where level(int) changed
     * from returning void to returning Item. The return value is not part of
     * SMM's semantics, so both forms are equivalent here.
     */
    public static void setLevel(Item item, int level) {
        if (item == null) {
            return;
        }

        Method setter = levelSetter();
        try {
            setter.invoke(item, level);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Item.level(int) is not accessible", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Item.level(int) failed", cause);
        }
    }

    private static synchronized Method levelSetter() {
        if (levelSetter != null) {
            return levelSetter;
        }

        for (Class<?> owner = Item.class; owner != null; owner = owner.getSuperclass()) {
            for (Method method : owner.getDeclaredMethods()) {
                if (!"level".equals(method.getName()) || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }

                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 1 || parameters[0] != int.class) {
                    continue;
                }

                Class<?> result = method.getReturnType();
                if (result != void.class && !Item.class.isAssignableFrom(result)) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                } catch (RuntimeException ignored) {
                    // Public/protected target methods remain invokable without this.
                }
                levelSetter = method;
                return method;
            }
        }

        throw new IllegalStateException("No compatible Item.level(int) method found in target");
    }
}
