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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * User-facing ModDebug console with fuzzy identifier resolution.
 *
 * This class keeps its helper code explicit so the compiled donor dependency
 * graph stays predictable under R8/desugaring.
 *
 * The '$' in this top-level class name is intentional: the APK/JAR injectors
 * already treat ModDebug$* as part of the self-contained ModDebug payload.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ModDebug$Console {

    private static final int MAX_SIMILAR = 8;

    private static final Comparator<Method> METHOD_KEY_COMPARATOR =
            new Comparator<Method>() {
                @Override
                public int compare(Method left, Method right) {
                    return methodKey(left).compareTo(methodKey(right));
                }
            };

    private static String lastCommand = "";

    private ModDebug$Console() {
    }

    public static void open() {
        GameScene.show(new WndTextInput(
                "Debug command",
                "help | give | spawn | affect | seed | trap | terrain | warp | inspect | use | enchant | inscribe | goto | where | macro | @ | !! | search | results | get | set | clear | save | load",
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
        Integer historyCount = historyRepeatCount(command);
        if (historyCount != null) {
            if (lastCommand.isEmpty()) {
                throw new IllegalStateException("No previous debug command");
            }
            runHistoryCommand(lastCommand, historyCount, 0, true);
            return;
        }

        if (command.contains("!!")) {
            if (lastCommand.isEmpty()) {
                throw new IllegalStateException("No previous debug command");
            }
            command = command.replace("!!", lastCommand);
            GLog.i("> " + command);
        }

        lastCommand = command;
        executeLine(command, 0, true);
    }

    private static Integer historyRepeatCount(String command) throws Exception {
        return (Integer) invokePrivate(
                "historyRepeatCount",
                new Class<?>[]{String.class},
                new Object[]{command});
    }

    private static void runHistoryCommand(
            String command, int count, int macroDepth, boolean topLevel)
            throws Exception {

        if (count > 1
                && commandOrMacroNeedsSelector(command, macroDepth)) {
            throw new IllegalArgumentException(
                    "Cannot repeat a command that opens an interactive selector more than once; "
                            + "supply an explicit cell/handle where the command supports one");
        }

        GLog.i(
                "> " + command
                        + (count == 1 ? "" : "  [" + count + " times]"));

        for (int i = 0; i < count; i++) {
            executeLine(command, macroDepth, topLevel);
        }
    }

    private static void executeLine(
            String command, int macroDepth, boolean topLevel) throws Exception {

        List<String> tokens = tokenize(command);
        if (tokens.isEmpty()) {
            return;
        }

        if (!isHandleAction(tokens)) {
            int commandIndex = commandIndex(tokens);
            if (commandIndex >= 0 && commandIndex < tokens.size()) {
                String name = tokens.get(commandIndex).toLowerCase(Locale.ROOT);
                if (!isBuiltInCommand(name)) {
                    List<String> macroArgs = new ArrayList<String>(
                            tokens.subList(commandIndex + 1, tokens.size()));
                    if (runMacro(name, macroArgs, macroDepth)) {
                        return;
                    }
                }
            }
        }

        String resolved = preprocess(command, tokens);
        if (!resolved.equals(command)) {
            command = resolved;
            tokens = tokenize(command);
        }

        int commandIndex = commandIndex(tokens);
        if (commandIndex >= 0
                && commandIndex < tokens.size()
                && "inspect".equalsIgnoreCase(tokens.get(commandIndex))
                && tokens.size() == commandIndex + 3) {
            inspect(tokens.get(commandIndex + 1), tokens.get(commandIndex + 2));
            return;
        }

        if (topLevel) {
            ModDebug.execute(command);
        } else {
            invokePrivate(
                    "executeExpanded",
                    new Class<?>[]{String.class, int.class},
                    new Object[]{command, macroDepth});
        }

    }

    private static String preprocess(
            String originalCommand, List<String> originalTokens) throws Exception {

        if (originalTokens.isEmpty() || isHandleAction(originalTokens)) {
            return originalCommand;
        }

        List<String> tokens = new ArrayList<String>(originalTokens);
        int commandIndex = commandIndex(tokens);
        if (commandIndex < 0 || commandIndex >= tokens.size()) {
            return originalCommand;
        }

        String command = tokens.get(commandIndex).toLowerCase(Locale.ROOT);
        if (!isBuiltInCommand(command)) {
            return originalCommand;
        }

        Class<?> commandClass = resolveCommandClass(tokens, commandIndex, command);

        if (("get".equals(command) || "set".equals(command))
                && tokens.size() > commandIndex + 2) {
            String targetToken = tokens.get(commandIndex + 1);
            if (!targetToken.startsWith("#")) {
                Class<?> fieldType = targetToken.startsWith("@")
                        ? targetInfo(targetToken).type
                        : commandClass;
                if (fieldType != null) {
                    int fieldIndex = commandIndex + 2;
                    Field field = resolveField(fieldType, tokens.get(fieldIndex));
                    if (!field.getName().equals(tokens.get(fieldIndex))) {
                        announce("field", field.getName(), tokens.get(fieldIndex));
                        tokens.set(fieldIndex, field.getName());
                    }

                    if ("set".equals(command) && tokens.size() > fieldIndex + 1) {
                        tokens.set(
                                fieldIndex + 1,
                                rewriteNamedValue(
                                        field.getType(), tokens.get(fieldIndex + 1)));
                    }
                }
            }
        }

        if ("use".equals(command) && tokens.size() > commandIndex + 2) {
            TargetInfo target = targetInfo(tokens.get(commandIndex + 1));
            resolveMethodAt(tokens, commandIndex + 2, target.type);
        } else if ("give".equals(command) && commandClass != null) {
            int methodIndex = commandIndex + 2;
            while (methodIndex < tokens.size()) {
                String token = tokens.get(methodIndex);
                if (token.matches("[+-]\\d+")
                        || token.matches("(?i)x\\d+")
                        || "-f".equalsIgnoreCase(token)
                        || "--force".equalsIgnoreCase(token)) {
                    methodIndex++;
                } else {
                    break;
                }
            }
            if (methodIndex < tokens.size()) {
                resolveMethodAt(tokens, methodIndex, commandClass);
            }
        } else if ("spawn".equals(command) && commandClass != null) {
            int methodIndex = commandIndex + 2;
            if (methodIndex < tokens.size()) {
                String token = tokens.get(methodIndex);
                if (token.matches("(?i)x\\d+")
                        || token.matches("[0-9]+")
                        || token.startsWith("@")) {
                    methodIndex++;
                }
            }
            if (methodIndex < tokens.size()) {
                resolveMethodAt(tokens, methodIndex, commandClass);
            }
        } else if ("affect".equals(command) && commandClass != null) {
            resolveAffectMethod(tokens, commandIndex, commandClass);
        }

        return joinTokens(tokens);
    }

    private static Class<?> resolveCommandClass(
            List<String> tokens, int commandIndex, String command) throws Exception {

        int classIndex = commandIndex + 1;
        if (classIndex >= tokens.size()) {
            return null;
        }

        String label;
        Class<?> parent;

        if ("give".equals(command)) {
            label = "Item class";
            parent = loadRequired(
                    "com.shatteredpixel.shatteredpixeldungeon.items.Item");
        } else if ("spawn".equals(command)) {
            label = "Mob class";
            parent = loadRequired(
                    "com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob");
        } else if ("affect".equals(command)) {
            label = "Buff class";
            parent = loadRequired(
                    "com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff");
        } else if ("seed".equals(command)) {
            label = "Blob class";
            parent = loadRequired(
                    "com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob");
        } else if ("trap".equals(command)) {
            label = "Trap class";
            parent = loadRequired(
                    "com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap");
        } else if ("get".equals(command) || "set".equals(command)) {
            String target = tokens.get(classIndex);
            if (target.startsWith("@")) {
                return targetInfo(target).type;
            }
            if (target.startsWith("#")) {
                return null;
            }
            label = "Class";
            parent = Object.class;
        } else if ("inspect".equals(command) || "use".equals(command)) {
            String target = tokens.get(classIndex);
            if (target.startsWith("@")
                    || "hero".equalsIgnoreCase(target)
                    || "level".equalsIgnoreCase(target)) {
                return targetInfo(target).type;
            }
            label = "Class";
            parent = Object.class;
        } else {
            return null;
        }

        String raw = tokens.get(classIndex);
        Class<?> type = resolveClass(raw, parent);
        if (type != null) {
            return type;
        }

        List<Class<?>> matches = bestClassMatches(raw, parent);
        if (matches.size() != 1) {
            throw classLookupError(label, raw, matches);
        }

        type = matches.get(0);
        announce("class", displayClassName(type, matches), raw);
        tokens.set(classIndex, type.getName());
        return type;
    }

    private static void resolveAffectMethod(
            List<String> tokens, int commandIndex, Class<?> buffType) throws Exception {

        int optionIndex = commandIndex + 2;
        if (optionIndex >= tokens.size()) {
            return;
        }

        Class<?> flavourBuff = loadRequired(
                "com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FlavourBuff");

        if (flavourBuff.isAssignableFrom(buffType)) {
            if (isFloat(tokens.get(optionIndex))) {
                optionIndex++;
            }
            if (optionIndex < tokens.size()) {
                resolveMethodAt(tokens, optionIndex, buffType);
            }
            return;
        }

        List<String> options = new ArrayList<String>(
                tokens.subList(optionIndex, tokens.size()));
        String[] common = {"set", "reset", "prolong", "extend"};
        for (String name : common) {
            MethodOption option = compatibleExactMethod(buffType, name, options);
            if (option != null) {
                replaceArguments(tokens, optionIndex, option.arguments);
                return;
            }
        }

        resolveMethodAt(tokens, optionIndex, buffType);
    }

    private static void resolveMethodAt(
            List<String> tokens, int methodIndex, Class<?> type) throws Exception {

        if (methodIndex >= tokens.size()) {
            return;
        }

        String rawName = tokens.get(methodIndex);
        List<String> rawArgs = new ArrayList<String>(
                tokens.subList(methodIndex + 1, tokens.size()));
        MethodOption option = resolveMethod(type, rawName, rawArgs);

        if (option == null) {
            return;
        }

        if (!option.name.equals(rawName)) {
            announce("method", option.name, rawName);
            tokens.set(methodIndex, option.name);
        }
        replaceArguments(tokens, methodIndex + 1, option.arguments);
    }

    private static MethodOption resolveMethod(
            Class<?> type, String rawName, List<String> rawArgs) throws Exception {

        List<Method> methods = allMethods(type);
        Collections.sort(methods, METHOD_KEY_COMPARATOR);

        List<Method> exactName = methodsByNameRank(methods, rawName, 0, -1);
        if (!exactName.isEmpty()) {
            List<Method> exactArity = methodsByNameRank(
                    exactName, rawName, 0, rawArgs.size());
            if (!exactArity.isEmpty()) {
                MethodOption option = firstCompatible(exactArity, rawArgs);
                if (option != null) {
                    return option;
                }
            }
            return new MethodOption(exactName.get(0).getName(), rawArgs);
        }

        for (int rank = 1; rank <= 3; rank++) {
            List<Method> ranked = methodsByNameRank(
                    methods, rawName, rank, rawArgs.size());
            if (ranked.isEmpty()) {
                continue;
            }

            Map<String, List<Method>> byName =
                    new LinkedHashMap<String, List<Method>>();
            for (Method method : ranked) {
                String key = method.getName().toLowerCase(Locale.ROOT);
                List<Method> overloads = byName.get(key);
                if (overloads == null) {
                    overloads = new ArrayList<Method>();
                    byName.put(key, overloads);
                }
                overloads.add(method);
            }

            List<MethodOption> options = new ArrayList<MethodOption>();
            for (List<Method> overloads : byName.values()) {
                MethodOption option = firstCompatible(overloads, rawArgs);
                if (option != null) {
                    options.add(option);
                }
            }

            if (options.size() == 1) {
                return options.get(0);
            }
            if (options.size() > 1) {
                throw methodLookupError(type, rawName, ranked);
            }
        }

        List<Method> similar = bestNamedMethods(methods, rawName);
        if (!similar.isEmpty()) {
            throw methodLookupError(type, rawName, similar);
        }
        return null;
    }

    private static MethodOption compatibleExactMethod(
            Class<?> type, String name, List<String> rawArgs) throws Exception {
        List<Method> methods = methodsByNameRank(
                allMethods(type), name, 0, rawArgs.size());
        return firstCompatible(methods, rawArgs);
    }

    private static MethodOption firstCompatible(
            List<Method> methods, List<String> rawArgs) throws Exception {

        List<Method> sorted = new ArrayList<Method>(methods);
        Collections.sort(sorted, METHOD_KEY_COMPARATOR);
        AmbiguousIdentifierException firstAmbiguous = null;

        for (Method method : sorted) {
            List<String> rewritten;
            try {
                rewritten = rewriteTypedArguments(
                        method.getParameterTypes(), rawArgs);
            } catch (AmbiguousIdentifierException ambiguous) {
                if (firstAmbiguous == null) {
                    firstAmbiguous = ambiguous;
                }
                continue;
            }

            Object converted;
            try {
                converted = invokePrivate(
                        "convertArgs",
                        new Class<?>[]{Class[].class, List.class},
                        new Object[]{method.getParameterTypes(), rewritten});
            } catch (Exception ignored) {
                continue;
            }
            if (converted != null) {
                return new MethodOption(method.getName(), rewritten);
            }
        }

        if (firstAmbiguous != null) {
            throw firstAmbiguous;
        }
        return null;
    }

    private static List<String> rewriteTypedArguments(
            Class<?>[] types, List<String> rawArgs) throws Exception {

        List<String> result = new ArrayList<String>(rawArgs);
        for (int i = 0; i < types.length && i < result.size(); i++) {
            result.set(i, rewriteNamedValue(types[i], result.get(i)));
        }
        return result;
    }

    private static String rewriteNamedValue(Class<?> type, String raw) throws Exception {
        if (type == Class.class) {
            Class<?> exact = resolveClass(raw, Object.class);
            if (exact != null) {
                return raw;
            }
            List<Class<?>> matches = bestClassMatches(raw, Object.class);
            if (matches.size() == 1) {
                return matches.get(0).getName();
            }
            if (matches.size() > 1) {
                throw new AmbiguousIdentifierException(
                        classLookupError("Class argument", raw, matches).getMessage());
            }
            return raw;
        }

        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            if (constants == null) {
                return raw;
            }

            for (Object constant : constants) {
                String name = ((Enum<?>) constant).name();
                if (name.equals(raw)) {
                    return raw;
                }
            }

            List<String> names = new ArrayList<String>();
            for (int rank = 0; rank <= 3; rank++) {
                names.clear();
                int bestScore = Integer.MAX_VALUE;
                for (Object constant : constants) {
                    String name = ((Enum<?>) constant).name();
                    if (matchRank(name, raw) != rank) {
                        continue;
                    }
                    if (rank == 3) {
                        int score = fuzzyScore(name, raw);
                        if (score < bestScore) {
                            names.clear();
                            bestScore = score;
                        } else if (score > bestScore) {
                            continue;
                        }
                    }
                    names.add(name);
                }
                if (!names.isEmpty()) {
                    break;
                }
            }

            if (names.size() == 1) {
                return names.get(0);
            }
            if (names.size() > 1) {
                throw new AmbiguousIdentifierException(
                        similarMessage("Enum value", raw, names));
            }
        }

        return raw;
    }

    private static void replaceArguments(
            List<String> tokens, int start, List<String> arguments) {
        for (int i = 0; i < arguments.size() && start + i < tokens.size(); i++) {
            tokens.set(start + i, arguments.get(i));
        }
    }

    private static List<Method> methodsByNameRank(
            List<Method> methods, String rawName, int rank, int arity) {
        List<Method> result = new ArrayList<Method>();
        int bestScore = Integer.MAX_VALUE;
        for (Method method : methods) {
            if (arity >= 0 && method.getParameterTypes().length != arity) {
                continue;
            }
            if (matchRank(method.getName(), rawName) != rank) {
                continue;
            }
            if (rank == 3) {
                int score = fuzzyScore(method.getName(), rawName);
                if (score < bestScore) {
                    result.clear();
                    bestScore = score;
                } else if (score > bestScore) {
                    continue;
                }
            }
            result.add(method);
        }
        return result;
    }

    private static List<Method> bestNamedMethods(
            List<Method> methods, String rawName) {
        for (int rank = 0; rank <= 3; rank++) {
            List<Method> result = methodsByNameRank(methods, rawName, rank, -1);
            if (!result.isEmpty()) {
                return result;
            }
        }
        return Collections.emptyList();
    }

    private static Field resolveField(Class<?> type, String rawName) throws Exception {
        Field exact = (Field) invokePrivate(
                "findField",
                new Class<?>[]{Class.class, String.class},
                new Object[]{type, rawName});
        if (exact != null) {
            return exact;
        }

        List<Field> fields = allFields(type);
        for (int rank = 0; rank <= 3; rank++) {
            Map<String, Field> matches = new LinkedHashMap<String, Field>();
            int bestScore = Integer.MAX_VALUE;
            for (Field field : fields) {
                if (matchRank(field.getName(), rawName) != rank) {
                    continue;
                }
                if (rank == 3) {
                    int score = fuzzyScore(field.getName(), rawName);
                    if (score < bestScore) {
                        matches.clear();
                        bestScore = score;
                    } else if (score > bestScore) {
                        continue;
                    }
                }
                String key = field.getName().toLowerCase(Locale.ROOT);
                if (!matches.containsKey(key)) {
                    matches.put(key, field);
                }
            }
            if (matches.size() == 1) {
                return matches.values().iterator().next();
            }
            if (matches.size() > 1) {
                List<String> names = new ArrayList<String>();
                for (Field field : matches.values()) {
                    names.add(field.getName());
                }
                throw new NoSuchFieldException(
                        similarMessage("Field", rawName, names));
            }
        }

        throw new NoSuchFieldException(type.getName() + "." + rawName);
    }

    private static List<Class<?>> bestClassMatches(
            String query, Class<?> parent) throws Exception {

        ensureClassIndex();
        final String needle = query.toLowerCase(Locale.ROOT);
        final boolean qualifiedQuery = query.indexOf('.') >= 0 || query.indexOf('$') >= 0;
        List<String> names = classNames();

        for (int rank = 1; rank <= 3; rank++) {
            List<Class<?>> matches = new ArrayList<Class<?>>();
            int bestScore = Integer.MAX_VALUE;

            for (String className : names) {
                String candidate = qualifiedQuery
                        ? className
                        : simpleClassName(className);
                if (matchRank(candidate, needle) != rank) {
                    continue;
                }

                if (rank == 3) {
                    int score = fuzzyScore(candidate, needle);
                    if (score < bestScore) {
                        matches.clear();
                        bestScore = score;
                    } else if (score > bestScore) {
                        continue;
                    }
                }

                Class<?> loaded = resolveClass(className, parent);
                if (loaded != null && !matches.contains(loaded)) {
                    matches.add(loaded);
                }
            }

            if (!matches.isEmpty()) {
                Collections.sort(matches, new Comparator<Class<?>>() {
                    @Override
                    public int compare(Class<?> left, Class<?> right) {
                        String leftName = qualifiedQuery
                                ? left.getName()
                                : left.getSimpleName();
                        String rightName = qualifiedQuery
                                ? right.getName()
                                : right.getSimpleName();
                        int leftDelta = Math.abs(leftName.length() - needle.length());
                        int rightDelta = Math.abs(rightName.length() - needle.length());
                        int byDelta = Integer.compare(leftDelta, rightDelta);
                        if (byDelta != 0) {
                            return byDelta;
                        }
                        int byName = leftName.compareToIgnoreCase(rightName);
                        if (byName != 0) {
                            return byName;
                        }
                        return left.getName().compareTo(right.getName());
                    }
                });
                return matches;
            }
        }
        return Collections.emptyList();
    }

    private static IllegalArgumentException classLookupError(
            String label, String raw, List<Class<?>> matches) {
        List<String> names = new ArrayList<String>();
        for (Class<?> match : matches) {
            names.add(displayClassName(match, matches));
        }
        return new IllegalArgumentException(
                similarMessage(label, raw, names));
    }

    private static NoSuchMethodException methodLookupError(
            Class<?> type, String raw, List<Method> methods) {
        Map<String, String> unique = new LinkedHashMap<String, String>();
        for (Method method : methods) {
            String key = method.getName().toLowerCase(Locale.ROOT);
            if (!unique.containsKey(key)) {
                unique.put(key, methodKey(method));
            }
        }
        return new NoSuchMethodException(similarMessage(
                "Method on " + type.getSimpleName(),
                raw,
                new ArrayList<String>(unique.values())));
    }

    private static String similarMessage(
            String label, String raw, List<String> matches) {
        StringBuilder message = new StringBuilder()
                .append(label)
                .append(" not found: ")
                .append(raw);
        if (!matches.isEmpty()) {
            message.append("\nSimilar:");
            int count = Math.min(MAX_SIMILAR, matches.size());
            for (int i = 0; i < count; i++) {
                message.append("\n  ").append(matches.get(i));
            }
            if (matches.size() > count) {
                message.append("\n  ... ")
                        .append(matches.size() - count)
                        .append(" more; refine the name.");
            }
        }
        return message.toString();
    }

    private static void inspect(String targetToken, String query) throws Exception {
        TargetInfo target = targetInfo(targetToken);
        List<Field> allFieldList = allFields(target.type);
        List<Method> allMethodList = allMethods(target.type);
        final String needle = query.toLowerCase(Locale.ROOT);

        List<Field> fields = new ArrayList<Field>();
        for (Field field : allFieldList) {
            if (matchRank(field.getName(), needle) >= 0) {
                fields.add(field);
            }
        }

        List<Method> methods = new ArrayList<Method>();
        for (Method method : allMethodList) {
            if (matchRank(method.getName(), needle) >= 0) {
                methods.add(method);
            }
        }

        Collections.sort(fields, new Comparator<Field>() {
            @Override
            public int compare(Field left, Field right) {
                int leftRank = matchRank(left.getName(), needle);
                int rightRank = matchRank(right.getName(), needle);
                int byRank = Integer.compare(leftRank, rightRank);
                if (byRank != 0) {
                    return byRank;
                }
                if (leftRank == 3) {
                    int byScore = Integer.compare(
                            fuzzyScore(left.getName(), needle),
                            fuzzyScore(right.getName(), needle));
                    if (byScore != 0) {
                        return byScore;
                    }
                }
                int byName = left.getName().compareToIgnoreCase(right.getName());
                if (byName != 0) {
                    return byName;
                }
                return left.getName().compareTo(right.getName());
            }
        });

        Collections.sort(methods, new Comparator<Method>() {
            @Override
            public int compare(Method left, Method right) {
                int leftRank = matchRank(left.getName(), needle);
                int rightRank = matchRank(right.getName(), needle);
                int byRank = Integer.compare(leftRank, rightRank);
                if (byRank != 0) {
                    return byRank;
                }
                if (leftRank == 3) {
                    int byScore = Integer.compare(
                            fuzzyScore(left.getName(), needle),
                            fuzzyScore(right.getName(), needle));
                    if (byScore != 0) {
                        return byScore;
                    }
                }
                return methodKey(left).compareToIgnoreCase(methodKey(right));
            }
        });

        StringBuilder out = new StringBuilder(target.type.getName());
        out.append("\nSearch: ").append(query);
        if (target.instance != null) {
            out.append("\nObject: ").append(debugName(target.instance));
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

    private static boolean runMacro(
            String name, List<String> args, int depth) throws Exception {

        loadMacros();
        String body = macros().get(name);
        if (body == null) {
            return false;
        }
        if (depth >= 8) {
            throw new IllegalStateException("Macro recursion limit reached");
        }

        List<String> expanded = new ArrayList<String>();
        String[] lines = body.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            expanded.add(expandMacroLine(trimmed, args));
        }

        String previousCommand = null;
        for (int i = 0; i < expanded.size(); i++) {
            String line = expanded.get(i);
            Integer historyCount = historyRepeatCount(line);

            if (historyCount != null) {
                if (previousCommand == null) {
                    throw new IllegalStateException(
                            "No previous command in this macro invocation");
                }
                GLog.i("> " + line);
                runHistoryCommand(
                        previousCommand, historyCount, depth + 1, false);
                continue;
            }

            if (i + 1 < expanded.size()
                    && commandOrMacroNeedsSelector(line, depth + 1)) {
                throw new IllegalArgumentException(
                        "Selector command must be the final macro line: " + line);
            }

            GLog.i("> " + line);
            executeLine(line, depth + 1, false);
            previousCommand = line;
        }
        return true;
    }

    private static String expandMacroLine(String line, List<String> args) {
        String expanded = line;
        for (int i = 9; i >= 1; i--) {
            String marker = "%" + i;
            if (!expanded.contains(marker)) {
                continue;
            }
            if (i > args.size()) {
                throw new IllegalArgumentException(
                        "Macro argument " + marker + " was not provided");
            }
            expanded = expanded.replace(marker, quoteToken(args.get(i - 1)));
        }
        return expanded;
    }

    private static boolean commandOrMacroNeedsSelector(
            String line, int macroDepth) throws Exception {
        Object result = invokePrivate(
                "commandOrMacroNeedsSelector",
                new Class<?>[]{String.class, int.class},
                new Object[]{line, macroDepth});
        return Boolean.TRUE.equals(result);
    }

    private static boolean isBuiltInCommand(String command) {
        if ("help".equals(command)
                || "give".equals(command)
                || "spawn".equals(command)
                || "affect".equals(command)
                || "seed".equals(command)
                || "trap".equals(command)
                || "terrain".equals(command)
                || "warp".equals(command)
                || "inspect".equals(command)
                || "use".equals(command)
                || "enchant".equals(command)
                || "inscribe".equals(command)
                || "goto".equals(command)
                || "where".equals(command)
                || "macro".equals(command)
                || "search".equals(command)
                || "results".equals(command)
                || "get".equals(command)
                || "set".equals(command)
                || "clear".equals(command)
                || "save".equals(command)
                || "load".equals(command)) {
            return true;
        }
        return false;
    }

    private static boolean isHandleAction(List<String> tokens) {
        if (tokens.size() < 2
                || !tokens.get(0).startsWith("@")
                || "@".equals(tokens.get(0))) {
            return false;
        }
        String action = tokens.get(1).toLowerCase(Locale.ROOT);
        return "inv".equals(action)
                || "inventory".equals(action)
                || "cell".equals(action)
                || "char".equals(action)
                || "character".equals(action)
                || "obj".equals(action)
                || "object".equals(action)
                || "hero".equals(action)
                || "level".equals(action)
                || "clear".equals(action)
                || "delete".equals(action);
    }

    private static int commandIndex(List<String> tokens) {
        if (tokens.isEmpty()) {
            return -1;
        }
        String first = tokens.get(0);
        if (first.startsWith("@") && !"@".equals(first) && tokens.size() > 1) {
            return 1;
        }
        return 0;
    }

    private static int matchRank(String name, String query) {
        String haystack = name.toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT);
        if (haystack.equals(needle)) {
            return 0;
        }
        if (haystack.startsWith(needle)) {
            return 1;
        }
        if (haystack.contains(needle)) {
            return 2;
        }
        return fuzzyScore(name, query) >= 0 ? 3 : -1;
    }

    private static int fuzzyScore(String name, String query) {
        String haystack = name.toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT);
        if (needle.length() < 3 || needle.length() > haystack.length()) {
            return -1;
        }

        int allowedGaps = Math.max(4, needle.length());
        int best = Integer.MAX_VALUE;

        for (int start = 0; start < haystack.length(); start++) {
            if (haystack.charAt(start) != needle.charAt(0)) {
                continue;
            }

            int previous = start;
            int searchAt = start + 1;
            int gaps = 0;
            int boundaries = isWordBoundary(name, start) ? 1 : 0;
            boolean matched = true;

            for (int q = 1; q < needle.length(); q++) {
                int found = -1;
                for (int i = searchAt; i < haystack.length(); i++) {
                    if (haystack.charAt(i) == needle.charAt(q)) {
                        found = i;
                        break;
                    }
                }
                if (found < 0) {
                    matched = false;
                    break;
                }

                gaps += found - previous - 1;
                previous = found;
                searchAt = found + 1;
                if (isWordBoundary(name, found)) {
                    boundaries++;
                }
            }

            if (!matched) {
                continue;
            }

            int span = previous - start + 1;
            if (gaps > allowedGaps || span > needle.length() * 2) {
                continue;
            }

            int score = gaps * 100
                    + start * 10
                    + (needle.length() - boundaries) * 2
                    + Math.max(0, name.length() - needle.length());
            if (score < best) {
                best = score;
            }
        }

        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private static boolean isWordBoundary(String name, int index) {
        if (index <= 0) {
            return true;
        }

        char current = name.charAt(index);
        char previous = name.charAt(index - 1);
        if (previous == '.'
                || previous == '$'
                || previous == '_'
                || previous == '-'
                || Character.isWhitespace(previous)) {
            return true;
        }
        if (Character.isUpperCase(current) && Character.isLowerCase(previous)) {
            return true;
        }
        return Character.isDigit(current) != Character.isDigit(previous);
    }

    private static String methodKey(Method method) {
        StringBuilder key = new StringBuilder(method.getName()).append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                key.append(',');
            }
            key.append(params[i].getSimpleName());
        }
        return key.append(')').toString();
    }

    private static TargetInfo targetInfo(String token) throws Exception {
        Object target = invokePrivate(
                "target",
                new Class<?>[]{String.class},
                new Object[]{token});
        Field typeField = target.getClass().getDeclaredField("type");
        Field instanceField = target.getClass().getDeclaredField("instance");
        typeField.setAccessible(true);
        instanceField.setAccessible(true);
        return new TargetInfo(
                (Class<?>) typeField.get(target),
                instanceField.get(target));
    }

    private static Class<?> resolveClass(String name, Class<?> parent) throws Exception {
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
        invokePrivate("ensureClassIndex", new Class<?>[0], new Object[0]);
    }

    private static List<String> classNames() throws Exception {
        Field field = ModDebug.class.getDeclaredField("CLASS_NAMES");
        field.setAccessible(true);
        return new ArrayList<String>((List<String>) field.get(null));
    }

    private static Map<String, String> macros() throws Exception {
        Field field = ModDebug.class.getDeclaredField("MACROS");
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }

    private static void loadMacros() throws Exception {
        invokePrivate("loadMacros", new Class<?>[0], new Object[0]);
    }

    private static List<Field> allFields(Class<?> type) throws Exception {
        return new ArrayList<Field>((List<Field>) invokePrivate(
                "allFields",
                new Class<?>[]{Class.class},
                new Object[]{type}));
    }

    private static List<Method> allMethods(Class<?> type) throws Exception {
        return new ArrayList<Method>((List<Method>) invokePrivate(
                "allMethods",
                new Class<?>[]{Class.class},
                new Object[]{type}));
    }

    private static String debugName(Object value) throws Exception {
        return String.valueOf(invokePrivate(
                "debugName",
                new Class<?>[]{Object.class},
                new Object[]{value}));
    }

    private static List<String> tokenize(String text) throws Exception {
        return new ArrayList<String>((List<String>) invokePrivate(
                "tokenize",
                new Class<?>[]{String.class},
                new Object[]{text}));
    }

    private static Object invokePrivate(
            String name, Class<?>[] parameterTypes, Object[] args) throws Exception {
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
            throw new RuntimeException(cause);
        }
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
        return className.substring(Math.max(dot, dollar) + 1);
    }

    private static boolean isFloat(String raw) {
        try {
            Float.parseFloat(raw);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static void announce(String kind, String resolved, String raw) {
        GLog.i("Using " + kind + " " + resolved + " for " + raw);
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
        if (!needsQuotes && token.indexOf('"') < 0 && token.indexOf('\\') < 0) {
            return token;
        }
        return "\""
                + token.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"";
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

    private static final class TargetInfo {
        final Class<?> type;
        final Object instance;

        TargetInfo(Class<?> type, Object instance) {
            this.type = type;
            this.instance = instance;
        }
    }

    private static final class MethodOption {
        final String name;
        final List<String> arguments;

        MethodOption(String name, List<String> arguments) {
            this.name = name;
            this.arguments = new ArrayList<String>(arguments);
        }
    }

    private static final class AmbiguousIdentifierException extends Exception {
        private static final long serialVersionUID = 1L;

        AmbiguousIdentifierException(String message) {
            super(message);
        }
    }
}
