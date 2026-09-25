package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.AdrenalineSurge;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Barkskin;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FlavourBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.GreaterHaste;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Healing;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.WellFed;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.elixirs.ElixirOfMight;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.utils.Callback;
import com.watabou.utils.Reflection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import com.spd.mod.tools.ModToolsWindow;

public class ModCharSelector extends CellSelector.Listener implements Callback {

    @FunctionalInterface
    private interface DurationCompatibilityHandler {
        boolean apply(Buff buff, float duration);
    }

    // Explicit handlers are only for buffs whose duration semantics are known.
    // This lets old SPD/fork implementations keep working without making unsafe
    // guesses about arbitrary numeric fields on unknown target-only buffs.
    private static final Map<Class<? extends Buff>, DurationCompatibilityHandler> DURATION_COMPATIBILITY = new HashMap<>();

    // Fork-only classes which are not on SMM's compile classpath can be registered
    // here by fully-qualified class name. Such rules must be based on verified target
    // source semantics; unknown fork-only buffs intentionally have no fallback.
    private static final Map<String, DurationCompatibilityHandler> OPTIONAL_DURATION_COMPATIBILITY = new HashMap<>();

    static {
        DURATION_COMPATIBILITY.put(AdrenalineSurge.class, (buff, duration) ->
                invokeCompatibleMethod(buff, "reset", new Class<?>[]{int.class, float.class}, 1, duration));

        DURATION_COMPATIBILITY.put(Barkskin.class, (buff, duration) ->
                invokeCompatibleMethod(buff, "set", new Class<?>[]{int.class, int.class},
                        1, Math.max(1, (int) duration)));

        DURATION_COMPATIBILITY.put(WellFed.class, (buff, duration) ->
                setKnownDurationField(buff, duration, "left"));

        DURATION_COMPATIBILITY.put(GreaterHaste.class, (buff, duration) -> {
            if (invokeCompatibleMethod(buff, "set", new Class<?>[]{int.class}, Math.max(1, (int) duration))) {
                return true;
            }
            return setKnownDurationField(buff, duration, "left");
        });
    }

    private Class<? extends Buff> buffClass;
    private boolean heroOnly;
    private boolean reselecting;
    private boolean isClosing;

    public ModCharSelector(Class<? extends Buff> buffClass) {
        this(buffClass, false);
    }

    private ModCharSelector(Class<? extends Buff> buffClass, boolean heroOnly) {
        super();
        this.buffClass = buffClass;
        this.heroOnly = heroOnly;
    }

    public static void start(Class<? extends Buff> buffClass) {
        if (ModBuffTab.heroOnly) {
            toggleBuff(Dungeon.hero, buffClass);
            return;
        }
        start(buffClass, false);
    }

    public static void startHeroOnly(Class<? extends Buff> buffClass) {
        if (ModBuffTab.heroOnly) {
            toggleBuff(Dungeon.hero, buffClass);
            return;
        }
        start(buffClass, true);
    }

    private static void start(Class<? extends Buff> buffClass, boolean heroOnly) {
        if (ModJournalWindow.instance != null) {
            ModJournalWindow.instance.hide();
        }
        if (ModToolsWindow.instance != null) {
            ModToolsWindow.instance.hide();
        }

        GameScene.selectCell(new ModCharSelector(buffClass, heroOnly));
    }

    private static Buff findBuff(Char target, Class<? extends Buff> buffClass) {
        if (target == null) {
            return null;
        }
        for (Buff buff : target.buffs(buffClass)) {
            return buff;
        }
        return null;
    }

    private static void toggleBuff(Char target, Class<? extends Buff> buffClass) {
        if (target == null) {
            GLog.w("No Hero available.", new Object[0]);
            return;
        }

        Buff buff = findBuff(target, buffClass);
        String format;
        Buff resultBuff;

        if (buff != null) {
            buff.detach();
            resultBuff = buff;
            format = "Detach %s";
        } else {
            resultBuff = Buff.affect(target, buffClass);
            if (resultBuff == null) {
                GLog.w("Unable to affect %s", buffClass.getSimpleName());
                return;
            }
            smartSetDuration(resultBuff, 1000000f);
            format = "Affect %s";
        }

        String displayName;
        try {
            Method m = resultBuff.getClass().getMethod("name");
            displayName = (String) m.invoke(resultBuff);
        } catch (Exception e) {
            displayName = buffClass.getSimpleName();
        }

        GLog.p(format, displayName);
    }

    @Override
    public String prompt() {
        String name;
        try {
            Object instance = Reflection.newInstance(buffClass);
            Method m = instance.getClass().getMethod("name");
            name = (String) m.invoke(instance);
        } catch (Exception e) {
            name = buffClass.getSimpleName();
        }
        return "Toggle " + name;
    }

    @Override
    public void call() {
        GameScene.selectCell(this);
    }

    @Override
    public void onSelect(Integer pos) {
        if (pos == null) {
            if (reselecting) {
                reselecting = false;
            } else if (!isClosing) {
                isClosing = true;
                GameScene.show(new ModJournalWindow());
            }
            return;
        }

        reselecting = true;
        Char target = Actor.findChar(pos);

        if (target == null) {
            ShatteredPixelDungeon.runOnRenderThread(this);
            return;
        }

        if (heroOnly && target != Dungeon.hero) {
            GLog.w("This buff can only affect the Hero.", new Object[0]);
            ShatteredPixelDungeon.runOnRenderThread(this);
            return;
        }

        toggleBuff(target, buffClass);
        ShatteredPixelDungeon.runOnRenderThread(this);
    }

    public static void smartSetDuration(Buff buff, float duration) {
        // Strategy 0: cases which need semantic initialization, not just duration.
        if (buff instanceof Healing) {
            Healing h = (Healing) buff;
            int amount = (h.target == null) ? 10 : (int) (h.target.HT * 0.8f + 14);
            h.setHeal(amount, 0.25f, 0);
            return;
        }

        if (buff instanceof ElixirOfMight.HTBoost) {
            ((ElixirOfMight.HTBoost) buff).reset();
            return;
        }

        if (buff instanceof Sungrass.Health) {
            Sungrass.Health sh = (Sungrass.Health) buff;
            int amount = (sh.target == null) ? 10 : sh.target.HT;
            sh.boost(amount);
            return;
        }

        if (buff instanceof Bleeding) {
            ((Bleeding) buff).set(30f);
            return;
        }

        if (buff instanceof Poison) {
            ((Poison) buff).set(30f);
            return;
        }

        // Journal enumeration creates unattached temporary Buffs for names/descriptions/icons.
        // Never run compatibility or field fallbacks on those preview objects.
        if (buff.target != null && tryCompatibilityDuration(buff, duration)) {
            return;
        }

        // Strategy 1: explicit single-argument duration APIs.
        try {
            Method m = buff.getClass().getMethod("extend", float.class);
            m.invoke(buff, duration);
            return;
        } catch (Exception ignore) {}

        try {
            Method m = buff.getClass().getMethod("delay", float.class);
            m.invoke(buff, duration);
            return;
        } catch (Exception ignore) {}

        try {
            Method m = buff.getClass().getMethod("set", float.class);
            m.invoke(buff, duration);
            return;
        } catch (Exception ignore) {}

        try {
            Method m = buff.getClass().getMethod("reset", int.class);
            m.invoke(buff, (int) duration);
            return;
        } catch (Exception ignore) {}

        // Strategy 2: standard FlavourBuff scheduling.
        if (buff instanceof FlavourBuff) {
            try {
                Method m = Actor.class.getDeclaredMethod("postpone", float.class);
                m.setAccessible(true);
                m.invoke(buff, duration);
                return;
            } catch (Exception ignore) {}
        }

        if (buff.target == null) {
            return;
        }

        // Strategy 3: conservative named-field fallback. Only names that clearly
        // express remaining duration are accepted. Generic "left"/"time" and
        // unique-numeric-field guessing are intentionally excluded for unknown buffs.
        String[] durationFields = {"duration", "turnsLeft", "remainingTurns", "turnsRemaining"};
        for (Class<?> c = buff.getClass(); c != null && c != Buff.class && Buff.class.isAssignableFrom(c); c = c.getSuperclass()) {
            for (String fieldName : durationFields) {
                try {
                    Field f = c.getDeclaredField(fieldName);
                    if (setNumericDurationField(buff, f, duration)) {
                        return;
                    }
                } catch (Exception ignore) {}
            }
        }

        // Unknown fork-only buffs deliberately stop here. If their duration semantics
        // are verified later, add an explicit compatibility handler instead of guessing.
    }

    private static boolean tryCompatibilityDuration(Buff buff, float duration) {
        DurationCompatibilityHandler handler = DURATION_COMPATIBILITY.get(buff.getClass());
        if (handler == null) {
            handler = OPTIONAL_DURATION_COMPATIBILITY.get(buff.getClass().getName());
        }
        if (handler == null) {
            return false;
        }
        try {
            return handler.apply(buff, duration);
        } catch (Exception ignore) {
            return false;
        }
    }

    private static boolean invokeCompatibleMethod(Buff buff, String preferredName,
                                                  Class<?>[] parameterTypes, Object... args) {
        try {
            Method m = buff.getClass().getMethod(preferredName, parameterTypes);
            m.invoke(buff, args);
            return true;
        } catch (Exception ignore) {}

        // R8 may rename methods. Structural matching is allowed only from an explicit
        // compatibility handler, where the class semantics have already been verified.
        Method candidate = null;
        for (Class<?> c = buff.getClass(); c != null && c != Buff.class && Buff.class.isAssignableFrom(c); c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.isSynthetic()
                        || m.getReturnType() != void.class
                        || !parameterTypesMatch(m.getParameterTypes(), parameterTypes)) {
                    continue;
                }
                if (candidate != null) {
                    return false;
                }
                candidate = m;
            }
        }

        if (candidate != null) {
            try {
                candidate.setAccessible(true);
                candidate.invoke(buff, args);
                return true;
            } catch (Exception ignore) {}
        }
        return false;
    }

    private static boolean parameterTypesMatch(Class<?>[] actual, Class<?>[] expected) {
        if (actual.length != expected.length) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean setKnownDurationField(Buff buff, float duration, String... preferredNames) {
        for (Class<?> c = buff.getClass(); c != null && c != Buff.class && Buff.class.isAssignableFrom(c); c = c.getSuperclass()) {
            for (String fieldName : preferredNames) {
                try {
                    Field f = c.getDeclaredField(fieldName);
                    if (setNumericDurationField(buff, f, duration)) {
                        return true;
                    }
                } catch (Exception ignore) {}
            }
        }

        // R8 may rename the field. Unique-numeric fallback is safe enough here because
        // this method is called only by an explicit handler for a verified buff class.
        Field candidate = null;
        for (Class<?> c = buff.getClass(); c != null && c != Buff.class && Buff.class.isAssignableFrom(c); c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic() || !isNumericField(f)) {
                    continue;
                }
                if (candidate != null) {
                    return false;
                }
                candidate = f;
            }
        }

        if (candidate != null) {
            try {
                return setNumericDurationField(buff, candidate, duration);
            } catch (Exception ignore) {}
        }
        return false;
    }

    private static boolean isNumericField(Field f) {
        Class<?> t = f.getType();
        return t == byte.class || t == Byte.class
                || t == short.class || t == Short.class
                || t == int.class || t == Integer.class
                || t == long.class || t == Long.class
                || t == float.class || t == Float.class
                || t == double.class || t == Double.class;
    }

    private static boolean setNumericDurationField(Buff buff, Field f, float duration) throws IllegalAccessException {
        if (Modifier.isStatic(f.getModifiers()) || !isNumericField(f)) {
            return false;
        }

        f.setAccessible(true);
        Class<?> t = f.getType();
        if (t == byte.class) {
            f.setByte(buff, (byte) duration);
        } else if (t == short.class) {
            f.setShort(buff, (short) duration);
        } else if (t == int.class) {
            f.setInt(buff, (int) duration);
        } else if (t == long.class) {
            f.setLong(buff, (long) duration);
        } else if (t == float.class) {
            f.setFloat(buff, duration);
        } else if (t == double.class) {
            f.setDouble(buff, duration);
        } else if (t == Byte.class) {
            f.set(buff, (byte) duration);
        } else if (t == Short.class) {
            f.set(buff, (short) duration);
        } else if (t == Integer.class) {
            f.set(buff, (int) duration);
        } else if (t == Long.class) {
            f.set(buff, (long) duration);
        } else if (t == Float.class) {
            f.set(buff, duration);
        } else if (t == Double.class) {
            f.set(buff, (double) duration);
        } else {
            return false;
        }
        return true;
    }
}
