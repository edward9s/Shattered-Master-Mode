package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sai;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Small runtime adapters for fork/minifier-sensitive combat members. */
final class ModCombatCompat {

    private ModCombatCompat() {
    }

    static void addDuelistComboHit(Hero hero, Char hitTarget) {
        if (hero == null) {
            return;
        }

        Object tracker = Buff.affect(hero, Sai.ComboStrikeTracker.class);
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
            // instance float timer. More complex tracker layouts are left to
            // the inject-time ABI profile rather than guessed here.
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

    /**
     * Invokes the target's native death path without linking the payload to a
     * fixed Char.die descriptor. Current SPD uses die(Object); some older
     * family targets expose die() instead.
     *
     * @return true when a compatible die method was found and invoked.
     */
    static boolean kill(Char target, Object cause) {
        if (target == null || !target.isAlive()) {
            return false;
        }

        try {
            Method method = findDieMethod(target.getClass(), cause, true);
            if (method == null) {
                method = findDieMethod(target.getClass(), cause, false);
            }
            if (method == null) {
                return false;
            }

            method.setAccessible(true);
            if (method.getParameterTypes().length == 0) {
                method.invoke(target);
            } else {
                method.invoke(target, cause);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Method findDieMethod(Class<?> targetClass, Object cause, boolean withCause) {
        for (Class<?> type = targetClass; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!"die".equals(method.getName())
                        || Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != Void.TYPE) {
                    continue;
                }

                Class<?>[] params = method.getParameterTypes();
                if (withCause) {
                    if (params.length == 1
                            && (cause == null || params[0].isInstance(cause))) {
                        return method;
                    }
                } else if (params.length == 0) {
                    return method;
                }
            }
        }
        return null;
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
