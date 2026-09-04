package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Console front-end for ModDebug with fuzzy member filtering for inspect.
 *
 * The '$' in this top-level class name is intentional: the APK/JAR injectors
 * already treat ModDebug$* as part of the self-contained ModDebug payload.
 */
@SuppressWarnings("unchecked")
public final class ModDebug$Console {

    private static String lastCommand = "";

    private ModDebug$Console() {
    }

    public static void open() {
        GameScene.show(new WndTextInput(
                "Debug command",
                "help | give | spawn | affect | seed | trap | warp | inspect | use | goto | where | macro | @ | search | results | get | set | clear | save | load",
                "",
                400,
                false,
                "Execute",
                "Cancel") {
            @Override
            public void onSelect(boolean positive, String text) {
                if (!positive) {
                    return;
                }

                String command = text == null ? "" : text.trim();
                if (command.isEmpty()) {
                    return;
                }

                try {
                    execute(command);
                } catch (Throwable error) {
                    reportCommandError("Debug command failed", error);
                }
            }
        });
    }

    private static void execute(String command) throws Exception {
        if (command.contains("!!")) {
            if (lastCommand.isEmpty()) {
                throw new IllegalStateException("No previous debug command");
            }
            command = command.replace("!!", lastCommand);
            GLog.i("> " + command);
        }

        lastCommand = command;
        List<String> args = tokenize(command);

        if (args.size() == 3
                && "inspect".equalsIgnoreCase(args.get(0))) {
            inspect(args.get(1), args.get(2));
            return;
        }

        ModDebug.execute(command);

        if (args.size() == 1
                && "help".equalsIgnoreCase(args.get(0))) {
            GLog.i("inspect <Class|hero|level|@variable> [query]  "
                    + "(case-insensitive fuzzy member search)");
        }
    }

    private static void inspect(String targetToken, String query)
            throws Exception {

        Object target = invokePrivate(
                "target",
                new Class<?>[]{String.class},
                new Object[]{targetToken});

        Field typeField = target.getClass().getDeclaredField("type");
        Field instanceField = target.getClass().getDeclaredField("instance");
        typeField.setAccessible(true);
        instanceField.setAccessible(true);

        Class<?> type = (Class<?>) typeField.get(target);
        Object instance = instanceField.get(target);

        List<Field> fields = new ArrayList<>((List<Field>) invokePrivate(
                "allFields",
                new Class<?>[]{Class.class},
                new Object[]{type}));
        List<Method> methods = new ArrayList<>((List<Method>) invokePrivate(
                "allMethods",
                new Class<?>[]{Class.class},
                new Object[]{type}));

        final String needle = query.toLowerCase(Locale.ROOT);

        fields.removeIf(field -> matchRank(field.getName(), needle) < 0);
        methods.removeIf(method -> matchRank(method.getName(), needle) < 0);

        Collections.sort(fields, new Comparator<Field>() {
            @Override
            public int compare(Field left, Field right) {
                int byRank = Integer.compare(
                        matchRank(left.getName(), needle),
                        matchRank(right.getName(), needle));
                if (byRank != 0) {
                    return byRank;
                }
                int byIgnoreCase = left.getName().compareToIgnoreCase(right.getName());
                if (byIgnoreCase != 0) {
                    return byIgnoreCase;
                }
                return left.getName().compareTo(right.getName());
            }
        });

        Collections.sort(methods, new Comparator<Method>() {
            @Override
            public int compare(Method left, Method right) {
                int byRank = Integer.compare(
                        matchRank(left.getName(), needle),
                        matchRank(right.getName(), needle));
                if (byRank != 0) {
                    return byRank;
                }
                return methodKey(left).compareToIgnoreCase(methodKey(right));
            }
        });

        StringBuilder out = new StringBuilder(type.getName());
        out.append("\nSearch: ").append(query);

        if (instance != null) {
            out.append("\nObject: ").append(debugName(instance));
        }

        if (fields.isEmpty() && methods.isEmpty()) {
            out.append("\nNo matching fields or methods.");
            GLog.i(out.toString());
            return;
        }

        if (!fields.isEmpty()) {
            out.append("\nFields:");
            for (Field field : fields) {
                out.append("\n  ")
                        .append(Modifier.toString(field.getModifiers()))
                        .append(' ')
                        .append(field.getType().getSimpleName())
                        .append(' ')
                        .append(field.getName());
            }
        }

        if (!methods.isEmpty()) {
            out.append("\nMethods:");
            for (Method method : methods) {
                out.append("\n  ")
                        .append(Modifier.toString(method.getModifiers()))
                        .append(' ')
                        .append(method.getReturnType().getSimpleName())
                        .append(' ')
                        .append(method.getName())
                        .append('(');

                Class<?>[] params = method.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) {
                        out.append(", ");
                    }
                    out.append(params[i].getSimpleName());
                }
                out.append(')');
            }
        }

        GLog.i(out.toString());
    }

    private static int matchRank(String name, String needle) {
        String haystack = name.toLowerCase(Locale.ROOT);

        if (haystack.equals(needle)) {
            return 0;
        }
        if (haystack.startsWith(needle)) {
            return 1;
        }
        if (haystack.contains(needle)) {
            return 2;
        }

        int at = 0;
        for (int i = 0; i < haystack.length() && at < needle.length(); i++) {
            if (haystack.charAt(i) == needle.charAt(at)) {
                at++;
            }
        }
        return at == needle.length() ? 3 : -1;
    }

    private static String methodKey(Method method) {
        StringBuilder key = new StringBuilder(method.getName()).append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                key.append(',');
            }
            key.append(params[i].getName());
        }
        return key.append(')').toString();
    }

    private static String debugName(Object value) throws Exception {
        return String.valueOf(invokePrivate(
                "debugName",
                new Class<?>[]{Object.class},
                new Object[]{value}));
    }

    private static Object invokePrivate(
            String name, Class<?>[] parameterTypes, Object[] args)
            throws Exception {

        Method method = ModDebug.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);

        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw error;
        }
    }

    private static List<String> tokenize(String command) throws Exception {
        return (List<String>) invokePrivate(
                "tokenize",
                new Class<?>[]{String.class},
                new Object[]{command});
    }

    private static void reportCommandError(String prefix, Throwable error) {
        error.printStackTrace();
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            GLog.n(prefix + ".");
        } else {
            GLog.n(prefix + ": " + error.getClass().getSimpleName() + ": " + message);
        }
    }
}
