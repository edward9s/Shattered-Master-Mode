package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Minimal compatibility adapter for invoking a Char's native death path. */
final class ModDeathCompat {

    private ModDeathCompat() {
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
}
