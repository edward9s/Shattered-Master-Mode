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
 * Console front-end for ModDebug with fuzzy member filtering for inspect and
 * friendly class-name resolution for interactive commands.
 *
 * The '$' in this top-level class name is intentional: the APK/JAR injectors
 * already treat ModDebug$* as part of the self-contained ModDebug payload.
 */
@SuppressWarnings("unchecked")
public final class ModDebug$Console {

    private static final int MAX_SIMILAR = 8;
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

        String resolvedCommand = resolveInteractiveClassOperand(command, args);
        if (!resolvedCommand.equals(command)) {
            command = resolvedCommand;
            args = tokenize(command);
        }

        int commandIndex = commandIndex(args);
        if (commandIndex >= 0
                && args.size() == commandIndex + 3
                && "inspect".equalsIgnoreCase(args.get(commandIndex))) {
            inspect(args.get(commandIndex + 1), args.get(commandIndex + 2));
            return;
        }

        ModDebug.execute(command);

        if (commandIndex == 0
                && args.size() == 1
                && "help".equalsIgnoreCase(args.get(0))) {
            GLog.i("inspect <Class|hero|level|@variable> [query]  "
                    + "(case-insensitive fuzzy member search)\n"
                    + "Class operands accept unique fuzzy matches; "
                    + "ambiguous names show Similar suggestions.");
        }
    }

    private static String resolveInteractiveClassOperand(
            String originalCommand, List<String> tokens) throws Exception {

        int commandIndex = commandIndex(tokens);
        if (commandIndex < 0 || commandIndex >= tokens.size()) {
            return originalCommand;
        }

        String command = tokens.get(commandIndex).toLowerCase(Locale.ROOT);
        int classIndex = commandIndex + 1;
        if (classIndex >= tokens.size()) {
            return originalCommand;
        }

        String label;
        Class<?> parent;

        switch (command) {
            case "give":
                label = "Item";
                parent = loadRequired(
                        "com.shatteredpixel.shatteredpixeldungeon.items.Item");
                break;

            case "spawn":
                label = "Mob";
                parent = loadRequired(
                        "com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob");
                break;

            case "affect":
                label = "Buff";
                parent = loadRequired(
                        "com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff");
                break;

            case "seed":
                label = "Blob";
                parent = loadRequired(
                        "com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob");
                break;

            case "trap":
                label = "Trap";
                parent = loadRequired(
                        "com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap");
                break;

            case "inspect":
            case "use":
                String target = tokens.get(classIndex);
                if (target.startsWith("@")
                        || "hero".equalsIgnoreCase(target)
                        || "level".equalsIgnoreCase(target)) {
                    return originalCommand;
                }
                label = "Class";
                parent = Object.class;
                break;

            default:
                return originalCommand;
        }

        String raw = tokens.get(classIndex);
        Class<?> exact = resolveClass(raw, parent);
        if (exact != null) {
            return originalCommand;
        }

        List<Class<?>> matches = bestClassMatches(raw, parent);
        if (matches.size() != 1) {
            throw classLookupError(label, raw, matches);
        }

        Class<?> resolved = matches.get(0);
        tokens.set(classIndex, resolved.getName());
        GLog.i("Using " + displayClassName(resolved, matches) + " for " + raw);

        return joinTokens(tokens);
    }

    private static int commandIndex(List<String> tokens) {
        if (tokens.isEmpty()) {
            return -1;
        }

        String first = tokens.get(0);
        if (first.startsWith("@")
                && !"@".equals(first)
                && tokens.size() > 1) {
            return 1;
        }

        return 0;
    }

    private static List<Class<?>> bestClassMatches(
            String query, Class<?> parent) throws Exception {

        ensureClassIndex();

        String needle = query.toLowerCase(Locale.ROOT);
        List<String> names = classNames();

        for (int rank = 1; rank <= 3; rank++) {
            if (rank == 3 && needle.length() < 3) {
                break;
            }

            List<Class<?>> matches = new ArrayList<>();

            for (String className : names) {
                String simple = simpleClassName(className);
                int simpleRank = matchRank(simple, needle);
                int fullRank = matchRank(className, needle);
                int candidateRank;

                if (simpleRank < 0) {
                    candidateRank = fullRank;
                } else if (fullRank < 0) {
                    candidateRank = simpleRank;
                } else {
                    candidateRank = Math.min(simpleRank, fullRank);
                }

                if (candidateRank != rank) {
                    continue;
                }

                Class<?> loaded = resolveClass(className, parent);
                if (loaded != null && !matches.contains(loaded)) {
                    matches.add(loaded);
                }
            }

            if (!matches.isEmpty()) {
                sortClassMatches(matches, needle);
                return matches;
            }
        }

        return Collections.emptyList();
    }

    private static void sortClassMatches(
            List<Class<?>> matches, final String needle) {

        Collections.sort(matches, new Comparator<Class<?>>() {
            @Override
            public int compare(Class<?> left, Class<?> right) {
                String leftName = left.getSimpleName();
                String rightName = right.getSimpleName();

                int leftDelta = Math.abs(leftName.length() - needle.length());
                int rightDelta = Math.abs(rightName.length() - needle.length());
                int byDelta = Integer.compare(leftDelta, rightDelta);
                if (byDelta != 0) {
                    return byDelta;
                }

                int byIgnoreCase = leftName.compareToIgnoreCase(rightName);
                if (byIgnoreCase != 0) {
                    return byIgnoreCase;
                }

                return left.getName().compareTo(right.getName());
            }
        });
    }

    private static IllegalArgumentException classLookupError(
            String label, String raw, List<Class<?>> matches) {

        StringBuilder message = new StringBuilder()
                .append(label)
                .append(" class not found: ")
                .append(raw);

        if (!matches.isEmpty()) {
            message.append("\nSimilar:");

            int count = Math.min(MAX_SIMILAR, matches.size());
            for (int i = 0; i < count; i++) {
                message.append("\n  ")
                        .append(displayClassName(matches.get(i), matches));
            }

            if (matches.size() > count) {
                message.append("\n  ... ")
                        .append(matches.size() - count)
                        .append(" more; refine the class name.");
            }
        }

        return new IllegalArgumentException(message.toString());
    }

    private static String displayClassName(
            Class<?> type, List<Class<?>> matches) {

        String simple = type.getSimpleName();
        if (simple == null || simple.isEmpty()) {
            return type.getName();
        }

        int same = 0;
        for (Class<?> candidate : matches) {
            if (simple.equals(candidate.getSimpleName())) {
                same++;
            }
        }

        return same > 1 ? type.getName() : simple;
    }

    private static String simpleClassName(String className) {
        int dot = className.lastIndexOf('.');
        int dollar = className.lastIndexOf('$');
        int split = Math.max(dot, dollar);
        return className.substring(split + 1);
    }

    private static String joinTokens(List<String> tokens) {
        StringBuilder result = new StringBuilder();

        for (String token : tokens) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(quoteToken(token));
        }

        return result.toString();
    }

    private static String quoteToken(String token) {
        if (token == null) {
            return "\"\"";
        }

        boolean needsQuotes = token.isEmpty();
        for (int i = 0; i < token.length() && !needsQuotes; i++) {
            if (Character.isWhitespace(token.charAt(i))) {
                needsQuotes = true;
            }
        }

        if (!needsQuotes
                && token.indexOf('"') < 0
                && token.indexOf('\\') < 0) {
            return token;
        }

        return "\""
                + token.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                + "\"";
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

    private static Class<?> resolveClass(
            String name, Class<?> parent) throws Exception {

        return (Class<?>) invokePrivate(
                "resolveClass",
                new Class<?>[]{String.class, Class.class},
                new Object[]{name, parent});
    }

    private static Class<?> loadRequired(String name) throws Exception {
        return (Class<?>) invokePrivate(
                "loadRequired",
                new Class<?>[]{String.class},
                new Object[]{name});
    }

    private static void ensureClassIndex() throws Exception {
        invokePrivate(
                "ensureClassIndex",
                new Class<?>[0],
                new Object[0]);
    }

    private static List<String> classNames() throws Exception {
        Field field = ModDebug.class.getDeclaredField("CLASS_NAMES");
        field.setAccessible(true);
        return new ArrayList<>((List<String>) field.get(null));
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
