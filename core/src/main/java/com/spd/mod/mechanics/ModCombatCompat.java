package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Runtime adapters for fork/minifier-sensitive Hero combat bookkeeping. */
final class ModCombatCompat {

    private static Class<? extends Buff> duelistComboTrackerClass;
    private static boolean duelistComboTrackerResolved;

    private ModCombatCompat() {
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Buff> resolveDuelistComboTrackerClass() {
        if (duelistComboTrackerResolved) {
            return duelistComboTrackerClass;
        }
        duelistComboTrackerResolved = true;

        try {
            Class<?> tracker = Class.forName(
                    "com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sai$ComboStrikeTracker",
                    false,
                    ModCombatCompat.class.getClassLoader());
            if (Buff.class.isAssignableFrom(tracker)) {
                duelistComboTrackerClass = (Class<? extends Buff>) tracker;
            }
        } catch (ClassNotFoundException | LinkageError ignored) {
            // Older targets can predate Duelist combo tracking entirely.
        }
        return duelistComboTrackerClass;
    }

    static void recordRiposteHit(Char riposter, Char hitTarget) {
        if (!(riposter instanceof Hero)) {
            return;
        }

        Hero hero = (Hero) riposter;

        Object subClass = readField(hero, "subClass");
        if ("GLADIATOR".equals(enumName(subClass))) {
            addGladiatorComboHit(hero, hitTarget);
        }

        Object heroClass = readField(hero, "heroClass");
        if ("DUELIST".equals(enumName(heroClass))) {
            addDuelistComboHit(hero, hitTarget);
        }
    }

    @SuppressWarnings("unchecked")
    private static void addGladiatorComboHit(Hero hero, Char hitTarget) {
        try {
            Class<?> rawCombo = Class.forName(
                    "com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Combo",
                    false,
                    ModCombatCompat.class.getClassLoader());
            if (!Buff.class.isAssignableFrom(rawCombo)) {
                return;
            }

            Object combo = Buff.affect(hero, (Class<? extends Buff>) rawCombo);
            if (combo == null) {
                return;
            }

            Method hit = rawCombo.getMethod("hit", Char.class);
            hit.invoke(combo, hitTarget);
        } catch (Exception | LinkageError ignored) {
            // Gladiator bookkeeping is auxiliary to the Riposte attack itself.
        }
    }

    private static Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Try the parent class.
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String enumName(Object value) {
        return value instanceof Enum ? ((Enum<?>) value).name() : null;
    }

    static void addDuelistComboHit(Hero hero, Char hitTarget) {
        if (hero == null) {
            return;
        }

        Class<? extends Buff> trackerType = resolveDuelistComboTrackerClass();
        if (trackerType == null) {
            return;
        }

        Object tracker = Buff.affect(hero, trackerType);
        if (tracker == null) {
            return;
        }

        try {
            Method method = findComboAddHitMethod(tracker.getClass());
            if (method != null) {
                method.setAccessible(true);
                if (method.getParameterTypes().length == 0) {
                    method.invoke(tracker);
                } else if (hitTarget != null) {
                    method.invoke(tracker, hitTarget);
                }
                return;
            }

            // Older/minified targets can inline the old no-argument addHit()
            // implementation. Only reproduce that old state update when the
            // tracker shape is unambiguous: one instance int counter and one
            // instance float timer.
            Field hits = null;
            Field time = null;
            for (Field field : tracker.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (field.getType() == Integer.TYPE) {
                    if (hits != null) {
                        return;
                    }
                    hits = field;
                } else if (field.getType() == Float.TYPE) {
                    if (time != null) {
                        return;
                    }
                    time = field;
                }
            }

            if (hits == null || time == null) {
                return;
            }
            hits.setAccessible(true);
            time.setAccessible(true);
            hits.setInt(tracker, hits.getInt(tracker) + 1);
            time.setFloat(tracker, 5f);
        } catch (Exception ignored) {
            // Combo tracking is auxiliary to the attack itself.
        }
    }

    private static Method findComboAddHitMethod(Class<?> trackerClass) {
        Method fallback = null;
        for (Method method : trackerClass.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != Void.TYPE) {
                continue;
            }

            Class<?>[] params = method.getParameterTypes();
            boolean supported = params.length == 0
                    || (params.length == 1 && params[0] == Char.class);
            if (!supported) {
                continue;
            }

            if ("addHit".equals(method.getName())) {
                return method;
            }
            if (fallback != null) {
                return null;
            }
            fallback = method;
        }
        return fallback;
    }
}
