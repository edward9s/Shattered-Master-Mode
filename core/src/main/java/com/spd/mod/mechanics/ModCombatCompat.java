package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Small runtime adapters for fork/minifier-sensitive combat members. */
final class ModCombatCompat {

    private static Method heroAttackTargetMethod;
    private static Field heroAttackTargetField;
    private static boolean heroAttackTargetResolved;

    private static Field hitMissIconField;
    private static boolean hitMissIconResolved;

    private static Class<? extends Buff> duelistComboTrackerClass;
    private static boolean duelistComboTrackerResolved;

    private ModCombatCompat() {
    }

    /**
     * Returns Hero's current attack target without linking the payload to one
     * particular Hero accessor/field name. Current SPD exposes attackTarget();
     * older/minified forks may expose only the underlying Char field.
     */
    static Char heroAttackTarget(Hero hero) {
        if (hero == null) {
            return null;
        }

        try {
            resolveHeroAttackTarget(hero.getClass());
            if (heroAttackTargetMethod != null) {
                Object value = heroAttackTargetMethod.invoke(hero);
                return value instanceof Char ? (Char) value : null;
            }
            if (heroAttackTargetField != null) {
                Object value = heroAttackTargetField.get(hero);
                return value instanceof Char ? (Char) value : null;
            }
        } catch (Exception ignored) {
            // Attack observation is an optional compatibility path.
        }
        return null;
    }

    private static void resolveHeroAttackTarget(Class<?> heroClass) {
        if (heroAttackTargetResolved) {
            return;
        }
        heroAttackTargetResolved = true;

        for (Method method : heroClass.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                    || method.getParameterTypes().length != 0
                    || !Char.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            if ("attackTarget".equals(method.getName())) {
                method.setAccessible(true);
                heroAttackTargetMethod = method;
                return;
            }
        }

        Field candidate = null;
        for (Field field : heroClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || !Char.class.isAssignableFrom(field.getType())) {
                continue;
            }
            if ("attackTarget".equals(field.getName())) {
                field.setAccessible(true);
                heroAttackTargetField = field;
                return;
            }
            if (candidate != null) {
                candidate = null;
                break;
            }
            candidate = field;
        }
        if (candidate != null) {
            candidate.setAccessible(true);
            heroAttackTargetField = candidate;
        }
    }

    static boolean hasInfiniteEvasionAgainst(Char defender, Char attacker) {
        return defender != null
                && attacker != null
                && defender.defenseSkill(attacker) >= Char.INFINITE_EVASION;
    }

    /**
     * Performs the native physical hit roll that Char.attack would have made if
     * invulnerability had not short-circuited it. This preserves ordinary miss
     * chance for Instant Kill when Infinite Accuracy is disabled.
     */
    static boolean rollNormalHeroHit(Hero hero, Char enemy) {
        if (hero == null || enemy == null || !hero.isAlive() || !enemy.isAlive()) {
            return false;
        }

        boolean hit = Char.hit(hero, enemy, 1f, false);
        clearHitMissIcon();
        return hit;
    }

    /**
     * Char.hit stores a reason icon for the enclosing Char.attack call. Our
     * standalone compatibility roll has no enclosing native attack branch, so
     * clear that transient cache to prevent it leaking into the next attack.
     */
    private static void clearHitMissIcon() {
        try {
            if (!hitMissIconResolved) {
                hitMissIconResolved = true;
                hitMissIconField = Char.class.getDeclaredField("hitMissIcon");
                hitMissIconField.setAccessible(true);
            }
            if (hitMissIconField != null) {
                hitMissIconField.setInt(null, -1);
            }
        } catch (Exception ignored) {
            // Cosmetic cleanup only; never let it affect combat.
        }
    }

    /**
     * Replays only the successful-hit side of Char.attack after the native hit
     * roll has been rejected by engine-level infinite evasion. This deliberately
     * lives entirely in SMM code: vanilla Char/ Hero/ enemy classes are not patched.
     *
     * The fallback preserves the core public combat pipeline (damage roll,
     * defenseProc, DR, attackProc and damage). It is intentionally used only for
     * the special INFINITE_EVASION case, never as a replacement for normal combat.
     */
    static boolean forceHeroHit(Hero hero, Char enemy, float dmgMulti, float dmgBonus) {
        if (hero == null || enemy == null || !hero.isAlive() || !enemy.isAlive()) {
            return false;
        }

        int dr = enemy.drRoll();
        float dmg = hero.damageRoll() * dmgMulti + dmgBonus;
        int effectiveDamage = enemy.defenseProc(hero, Math.round(dmg));

        if (effectiveDamage >= 0) {
            effectiveDamage = Math.max(effectiveDamage - dr, 0);
            effectiveDamage = hero.attackProc(enemy, effectiveDamage);
        }

        // defenseProc may intentionally return a negative sentinel to suppress
        // on-hit behavior; in that case the forced hit is still consumed but no
        // damage call is made.
        if (effectiveDamage >= 0 && enemy.isAlive()) {
            enemy.damage(effectiveDamage, hero);
        }
        return true;
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
            Method method = findNamedDieMethod(target.getClass(), cause, true);
            if (method == null) {
                // R8/minified targets may rename die(Object). A single
                // instance void(Object) method on one hierarchy level is a
                // sufficiently narrow structural match; ambiguity is rejected.
                method = findStructuralDieWithCause(target.getClass(), cause);
            }
            if (method == null) {
                method = findNamedDieMethod(target.getClass(), cause, false);
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

    private static Method findNamedDieMethod(Class<?> targetClass, Object cause, boolean withCause) {
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

    private static Method findStructuralDieWithCause(Class<?> targetClass, Object cause) {
        for (Class<?> type = targetClass; type != null; type = type.getSuperclass()) {
            Method candidate = null;
            boolean ambiguous = false;

            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != Void.TYPE) {
                    continue;
                }

                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1
                        || params[0] != Object.class
                        || (cause != null && !params[0].isInstance(cause))) {
                    continue;
                }

                if (candidate != null) {
                    ambiguous = true;
                    break;
                }
                candidate = method;
            }

            if (!ambiguous && candidate != null) {
                return candidate;
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
