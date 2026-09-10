package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.PinCushion;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.watabou.noosa.Image;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Iterator;

/**
 * Runtime adapters used by the small ModAnkh/Store/Loot/Console payload.
 *
 * Keep version-sensitive SPD calls behind reflection so an injection donor built
 * against a modern SPD can still run on older SPD-family forks when the
 * underlying behavior has a safe equivalent.
 */
public final class ModLegacyCompat {

    private static final float DEFAULT_LONG_CLICK = 0.25f;

    private ModLegacyCompat() {
    }

    public static int itemIcon(String name, int fallback) {
        try {
            Class<?> icons = Class.forName(
                    "com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet$Icons");
            Field field = icons.getField(name);
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Integer.TYPE) {
                return field.getInt(null);
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Old targets predate ItemSpriteSheet.Icons.
        }
        return fallback;
    }

    public static Image currencyIcon(String name) {
        try {
            Class<?> icons = Class.forName(
                    "com.shatteredpixel.shatteredpixeldungeon.ui.Icons");
            if (icons.isEnum()) {
                Object value = null;
                for (Object constant : icons.getEnumConstants()) {
                    if (constant instanceof Enum
                            && name.equals(((Enum<?>) constant).name())) {
                        value = constant;
                        break;
                    }
                }
                if (value != null) {
                    Method get = icons.getMethod("get");
                    Object image = get.invoke(value);
                    if (image instanceof Image) {
                        return (Image) image;
                    }
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Fall through to the universally available gold item sprite.
        }
        return new ItemSprite(ItemSpriteSheet.GOLD, null);
    }

    public static float longClickThreshold() {
        String[] owners = {
                "com.shatteredpixel.shatteredpixeldungeon.ui.Button",
                "com.watabou.noosa.ui.Button"
        };
        for (String owner : owners) {
            try {
                Field field = Class.forName(owner).getField("longClick");
                if (Modifier.isStatic(field.getModifiers())) {
                    Object value = field.get(null);
                    if (value instanceof Number) {
                        return ((Number) value).floatValue();
                    }
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Try the other known Button owner.
            }
        }
        return DEFAULT_LONG_CLICK;
    }

    public static boolean vibrationEnabled() {
        try {
            Class<?> settings = Class.forName(
                    "com.shatteredpixel.shatteredpixeldungeon.SPDSettings");
            Method method = settings.getMethod("vibration");
            Object value = method.invoke(null);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Older targets vibrated long-clicks without a separate setting.
        }
        return true;
    }

    public static Item grabOne(PinCushion pin) {
        if (pin == null) {
            return null;
        }

        try {
            Method method = pin.getClass().getMethod("grabOne");
            Object value = method.invoke(pin);
            if (value == null || value instanceof Item) {
                return (Item) value;
            }
        } catch (NoSuchMethodException ignored) {
            // Pre-grabOne targets expose the backing stuck-item collection instead.
        } catch (IllegalAccessException | InvocationTargetException ignored) {
            return null;
        }

        for (Class<?> owner = pin.getClass(); owner != null; owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !Collection.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(pin);
                    if (!(value instanceof Collection)) {
                        continue;
                    }
                    Collection<?> collection = (Collection<?>) value;
                    Iterator<?> iterator = collection.iterator();
                    while (iterator.hasNext()) {
                        Object candidate = iterator.next();
                        if (candidate instanceof Item) {
                            iterator.remove();
                            if (collection.isEmpty()) {
                                pin.detach();
                            }
                            return (Item) candidate;
                        }
                    }
                } catch (RuntimeException | IllegalAccessException ignored) {
                    // Try another collection field if this one cannot be inspected.
                }
            }
        }
        return null;
    }

    public static boolean pickUpDew(Item item, Hero hero, int pos) {
        if (item == null || hero == null) {
            return false;
        }

        // Modern Dewdrop overrides the position-aware pickup. Prefer that exact
        // declaration rather than accidentally resolving an inherited Item method.
        try {
            Method method = item.getClass().getDeclaredMethod(
                    "doPickUp", Hero.class, Integer.TYPE);
            method.setAccessible(true);
            Object value = method.invoke(item, hero, pos);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (NoSuchMethodException ignored) {
            // Old ARK-style targets use doPickUp_auto(Hero) for no-time pickup.
        } catch (IllegalAccessException | InvocationTargetException ignored) {
            return false;
        }

        try {
            Method method = item.getClass().getDeclaredMethod("doPickUp_auto", Hero.class);
            method.setAccessible(true);
            Object value = method.invoke(item, hero);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (NoSuchMethodException ignored) {
            // Fall back to the standard target Item pickup API below.
        } catch (IllegalAccessException | InvocationTargetException ignored) {
            return false;
        }

        return item.doPickUp(hero, pos);
    }

    public static void restoreCooldown(Hero hero, float delta) {
        if (hero == null || delta == 0f) {
            return;
        }

        Method method = findFloatMethod(hero.getClass(), "spendConstant");
        if (method == null) {
            method = findFloatMethod(hero.getClass(), "spend");
        }
        if (method == null) {
            throw new IllegalStateException(
                    "Target Hero has no compatible spendConstant(float) or spend(float)");
        }

        try {
            method.setAccessible(true);
            method.invoke(hero, delta);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Target Hero time adjustment is inaccessible", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Target Hero time adjustment failed", cause);
        }
    }

    private static Method findFloatMethod(Class<?> start, String name) {
        for (Class<?> owner = start; owner != null; owner = owner.getSuperclass()) {
            try {
                Method method = owner.getDeclaredMethod(name, Float.TYPE);
                if (!Modifier.isStatic(method.getModifiers())
                        && method.getReturnType() == Void.TYPE) {
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                // Keep walking the hierarchy.
            }
        }
        return null;
    }
}
