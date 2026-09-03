package com.spd.mod.debug;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FlavourBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Small in-game reflection console for SMM experiments.
 *
 * This intentionally lives entirely in com.spd.mod: target forks do not need
 * WndUseItem, PlatformSupport, or GameScene patches. ModAnkh exposes it through
 * a normal Item action.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class ModDebug {

    private static final String[] ROOTS = {
            "com.shatteredpixel.shatteredpixeldungeon",
            "com.spd.mod"
    };

    private static final Object BAD_ARG = new Object();
    private static final List<String> CLASS_NAMES = new ArrayList<>();
    private static boolean indexed;

    private ModDebug() {
    }

    public static void open() {
        GameScene.show(new WndTextInput(
                "Debug command",
                "help | give | spawn | affect | inspect | use",
                "",
                200,
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
                    String message = error.getMessage();
                    GLog.n("Debug command failed: " + error.getClass().getSimpleName()
                            + (message == null || message.isEmpty() ? "" : ": " + message));
                    error.printStackTrace();
                }
            }
        });
    }

    public static void execute(String commandLine) throws Exception {
        List<String> args = tokenize(commandLine);
        if (args.isEmpty()) {
            return;
        }

        String command = args.remove(0).toLowerCase(Locale.ROOT);
        switch (command) {
            case "help":
                help();
                break;
            case "give":
                give(args);
                break;
            case "spawn":
                spawn(args);
                break;
            case "affect":
                affect(args);
                break;
            case "inspect":
                inspect(args);
                break;
            case "use":
                use(args);
                break;
            default:
                GLog.w("Unknown debug command: " + command + ". Type 'help'.");
                break;
        }
    }

    private static void help() {
        GLog.i(
                "Debug commands:\n"
                + "give <Item> [+level] [xquantity]\n"
                + "spawn <Mob> [xquantity]\n"
                + "affect <Buff> [duration]  (applies to hero)\n"
                + "inspect <Class|hero|level>\n"
                + "use <Class|hero|level> <method> [args...]\n"
                + "Class names may be simple (RingOfEnergy) or fully qualified.\n"
                + "Quoted strings are supported for method arguments."
        );
    }

    private static void give(List<String> args) throws Exception {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("give <Item> [+level] [xquantity]");
        }

        Class<?> raw = resolveClass(args.get(0), Item.class);
        if (raw == null) {
            throw new IllegalArgumentException("Item class not found: " + args.get(0));
        }

        Integer level = null;
        int quantity = 1;
        for (int i = 1; i < args.size(); i++) {
            String token = args.get(i);
            if (token.matches("[+-]\\d+")) {
                level = Integer.parseInt(token);
            } else if (token.matches("(?i)x\\d+")) {
                quantity = boundedCount(Integer.parseInt(token.substring(1)));
            } else {
                throw new IllegalArgumentException("Unrecognized give argument: " + token);
            }
        }

        int made = 0;
        for (int i = 0; i < quantity; i++) {
            Item item = (Item) newInstance(raw);
            if (level != null) {
                item.level(level);
            }
            if (!item.collect()) {
                GLog.w("Backpack full; stopped after " + made + " item(s).");
                break;
            }
            made++;
        }

        GLog.p("Created " + made + " x " + raw.getSimpleName()
                + (level == null ? "" : " (level " + level + ")"));
    }

    private static void spawn(List<String> args) throws Exception {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("spawn <Mob> [xquantity]");
        }
        if (Dungeon.level == null) {
            throw new IllegalStateException("No active level");
        }

        Class<?> raw = resolveClass(args.get(0), Mob.class);
        if (raw == null) {
            throw new IllegalArgumentException("Mob class not found: " + args.get(0));
        }

        int quantity = 1;
        if (args.size() > 1) {
            String token = args.get(1);
            if (!token.matches("(?i)x\\d+")) {
                throw new IllegalArgumentException("spawn quantity must look like x3");
            }
            quantity = boundedCount(Integer.parseInt(token.substring(1)));
        }
        if (args.size() > 2) {
            throw new IllegalArgumentException("spawn <Mob> [xquantity]");
        }

        int made = 0;
        for (int i = 0; i < quantity; i++) {
            Mob mob = (Mob) newInstance(raw);
            int cell = Dungeon.level.randomRespawnCell(mob);
            if (cell < 0) {
                break;
            }
            mob.pos = cell;
            GameScene.add(mob);
            made++;
        }

        GLog.p("Spawned " + made + " x " + raw.getSimpleName());
    }

    private static void affect(List<String> args) throws Exception {
        if (args.isEmpty()) {
            throw new IllegalArgumentException("affect <Buff> [duration]");
        }

        Hero hero = Dungeon.hero;
        if (hero == null) {
            throw new IllegalStateException("No active hero");
        }

        Class<?> raw = resolveClass(args.get(0), Buff.class);
        if (raw == null) {
            throw new IllegalArgumentException("Buff class not found: " + args.get(0));
        }

        Float duration = null;
        if (args.size() > 1) {
            duration = Float.parseFloat(args.get(1));
        }
        if (args.size() > 2) {
            throw new IllegalArgumentException("affect <Buff> [duration]");
        }

        Buff buff;
        if (duration != null && FlavourBuff.class.isAssignableFrom(raw)) {
            buff = Buff.affect(hero, (Class) raw, duration);
        } else {
            buff = Buff.affect(hero, (Class) raw);
            if (duration != null) {
                GLog.w("Duration ignored: " + raw.getSimpleName() + " is not a FlavourBuff.");
            }
        }

        GLog.p("Affected hero with " + buff.getClass().getSimpleName());
    }

    private static void inspect(List<String> args) throws Exception {
        if (args.size() != 1) {
            throw new IllegalArgumentException("inspect <Class|hero|level>");
        }

        TargetRef target = target(args.get(0));
        Class<?> type = target.type;

        List<Field> fields = allFields(type);
        List<Method> methods = allMethods(type);
        fields.sort(Comparator.comparing(Field::getName));
        methods.sort(Comparator.comparing(ModDebug::methodKey));

        StringBuilder out = new StringBuilder(type.getName());

        if (!fields.isEmpty()) {
            out.append("\nFields:");
            int count = 0;
            for (Field field : fields) {
                if (count++ >= 40) {
                    out.append("\n  ...");
                    break;
                }
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
            int count = 0;
            for (Method method : methods) {
                if (count++ >= 60) {
                    out.append("\n  ...");
                    break;
                }
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

    private static void use(List<String> args) throws Exception {
        if (args.size() < 2) {
            throw new IllegalArgumentException(
                    "use <Class|hero|level> <method> [args...]");
        }

        TargetRef ref = target(args.get(0));
        String name = args.get(1);
        List<String> rawArgs = args.subList(2, args.size());

        List<Method> candidates = allMethods(ref.type);
        candidates.sort(Comparator.comparing(ModDebug::methodKey));

        Exception lastError = null;
        for (Method method : candidates) {
            if (!method.getName().equalsIgnoreCase(name)
                    || method.getParameterTypes().length != rawArgs.size()) {
                continue;
            }

            Object[] converted = convertArgs(method.getParameterTypes(), rawArgs);
            if (converted == null) {
                continue;
            }

            Object receiver = null;
            if (!Modifier.isStatic(method.getModifiers())) {
                receiver = ref.instance != null ? ref.instance : newInstance(ref.type);
            }

            try {
                method.setAccessible(true);
                Object result = method.invoke(receiver, converted);
                GLog.p(method.getName() + " -> " + valueString(result));
                return;
            } catch (Exception error) {
                lastError = error;
            }
        }

        if (lastError != null) {
            throw lastError;
        }

        throw new NoSuchMethodException(
                "No compatible " + ref.type.getSimpleName() + "." + name
                        + " with " + rawArgs.size() + " argument(s)");
    }

    private static TargetRef target(String token) throws Exception {
        if ("hero".equalsIgnoreCase(token)) {
            if (Dungeon.hero == null) {
                throw new IllegalStateException("No active hero");
            }
            return new TargetRef(Dungeon.hero.getClass(), Dungeon.hero);
        }

        if ("level".equalsIgnoreCase(token)) {
            if (Dungeon.level == null) {
                throw new IllegalStateException("No active level");
            }
            return new TargetRef(Dungeon.level.getClass(), Dungeon.level);
        }

        Class<?> type = resolveClass(token, Object.class);
        if (type == null) {
            throw new ClassNotFoundException(token);
        }
        return new TargetRef(type, null);
    }

    private static Object[] convertArgs(Class<?>[] types, List<String> raw) throws Exception {
        Object[] result = new Object[types.length];

        for (int i = 0; i < types.length; i++) {
            Object value;
            try {
                value = convertArg(types[i], raw.get(i));
            } catch (RuntimeException parseError) {
                return null;
            }

            if (value == BAD_ARG) {
                return null;
            }
            result[i] = value;
        }

        return result;
    }

    private static Object convertArg(Class<?> type, String raw) throws Exception {
        if ("null".equalsIgnoreCase(raw)) {
            return type.isPrimitive() ? BAD_ARG : null;
        }

        if (type == String.class || type == CharSequence.class) {
            return raw;
        }

        if (type == boolean.class || type == Boolean.class) {
            if ("true".equalsIgnoreCase(raw)) return true;
            if ("false".equalsIgnoreCase(raw)) return false;
            return BAD_ARG;
        }

        if (type == byte.class || type == Byte.class) return Byte.parseByte(raw);
        if (type == short.class || type == Short.class) return Short.parseShort(raw);
        if (type == int.class || type == Integer.class) return Integer.parseInt(raw);
        if (type == long.class || type == Long.class) return Long.parseLong(raw);
        if (type == float.class || type == Float.class) return Float.parseFloat(raw);
        if (type == double.class || type == Double.class) return Double.parseDouble(raw);

        if (type == char.class || type == Character.class) {
            return raw.length() == 1 ? raw.charAt(0) : BAD_ARG;
        }

        if (type == Class.class) {
            Class<?> cls = resolveClass(raw, Object.class);
            return cls == null ? BAD_ARG : cls;
        }

        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(raw)) {
                    return constant;
                }
            }
            return BAD_ARG;
        }

        if (Dungeon.hero != null
                && "hero".equalsIgnoreCase(raw)
                && type.isInstance(Dungeon.hero)) {
            return Dungeon.hero;
        }

        if (Dungeon.level != null
                && "level".equalsIgnoreCase(raw)
                && type.isInstance(Dungeon.level)) {
            return Dungeon.level;
        }

        Class<?> cls = resolveClass(raw, type);
        if (cls != null && type.isAssignableFrom(cls)) {
            try {
                return newInstance(cls);
            } catch (Exception ignored) {
                return BAD_ARG;
            }
        }

        return BAD_ARG;
    }

    private static Object newInstance(Class<?> type) throws Exception {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            throw new InstantiationException("Cannot instantiate " + type.getName());
        }

        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static int boundedCount(int count) {
        if (count < 1 || count > 100) {
            throw new IllegalArgumentException("quantity must be between 1 and 100");
        }
        return count;
    }

    private static Class<?> resolveClass(String input, Class<?> parent) {
        String name = input.trim();

        Class<?> direct = tryLoad(name, parent);
        if (direct != null) {
            return direct;
        }

        for (String root : ROOTS) {
            direct = tryLoad(root + "." + name, parent);
            if (direct != null) {
                return direct;
            }
        }

        ensureClassIndex();

        String lower = name.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String className : CLASS_NAMES) {
            String fullLower = className.toLowerCase(Locale.ROOT);
            int dot = className.lastIndexOf('.');
            int dollar = className.lastIndexOf('$');
            int split = Math.max(dot, dollar);
            String simple = className.substring(split + 1);

            if (className.equalsIgnoreCase(name)
                    || fullLower.endsWith("." + lower)
                    || fullLower.endsWith("$" + lower)
                    || simple.equalsIgnoreCase(name)) {
                matches.add(className);
            }
        }

        matches.sort(
                Comparator.comparingInt(String::length)
                        .thenComparing(Comparator.naturalOrder()));

        for (String candidate : matches) {
            Class<?> loaded = tryLoad(candidate, parent);
            if (loaded != null) {
                return loaded;
            }
        }

        return null;
    }

    private static Class<?> tryLoad(String name, Class<?> parent) {
        try {
            ClassLoader loader = ModDebug.class.getClassLoader();
            Class<?> type = Class.forName(name, false, loader);
            return parent == null || parent.isAssignableFrom(type) ? type : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static synchronized void ensureClassIndex() {
        if (indexed) {
            return;
        }
        indexed = true;

        Set<String> names = new HashSet<>();
        boolean android = indexAndroid(names);
        if (!android) {
            indexDesktop(names);
        }

        CLASS_NAMES.addAll(names);
        Collections.sort(CLASS_NAMES);

        if (CLASS_NAMES.isEmpty()) {
            GLog.w("Debug class index is empty; use fully-qualified class names.");
        }
    }

    private static boolean indexAndroid(Set<String> names) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application =
                    activityThread.getMethod("currentApplication").invoke(null);
            if (application == null) {
                return false;
            }

            Method getPackageCodePath =
                    application.getClass().getMethod("getPackageCodePath");
            String path = (String) getPackageCodePath.invoke(application);

            Class<?> dexFileClass = Class.forName("dalvik.system.DexFile");
            Object dexFile =
                    dexFileClass.getConstructor(String.class).newInstance(path);
            Enumeration<?> entries =
                    (Enumeration<?>) dexFileClass.getMethod("entries").invoke(dexFile);

            while (entries.hasMoreElements()) {
                Object next = entries.nextElement();
                if (next instanceof String) {
                    addIndexedName(names, (String) next);
                }
            }

            try {
                dexFileClass.getMethod("close").invoke(dexFile);
            } catch (Throwable ignored) {
                // close() is not present on every supported Android runtime.
            }

            return true;

        } catch (ClassNotFoundException notAndroid) {
            return false;

        } catch (Throwable error) {
            GLog.w("Android class scan failed; fully-qualified names still work.");
            error.printStackTrace();
            return true;
        }
    }

    private static void indexDesktop(Set<String> names) {
        try {
            URL location =
                    ModDebug.class.getProtectionDomain().getCodeSource().getLocation();

            if (location == null
                    || !"file".equalsIgnoreCase(location.getProtocol())) {
                return;
            }

            File path =
                    new File(URLDecoder.decode(location.getPath(), "UTF-8"));

            if (path.isFile()) {
                try (JarFile jar = new JarFile(path)) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String entry = entries.nextElement().getName();
                        if (entry.endsWith(".class")) {
                            addIndexedName(
                                    names,
                                    entry.substring(0, entry.length() - 6)
                                            .replace('/', '.'));
                        }
                    }
                }

            } else if (path.isDirectory()) {
                indexDirectory(names, path, path);
            }

        } catch (Throwable error) {
            GLog.w("Desktop class scan failed; fully-qualified names still work.");
            error.printStackTrace();
        }
    }

    private static void indexDirectory(
            Set<String> names, File root, File directory) {

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                indexDirectory(names, root, file);

            } else if (file.getName().endsWith(".class")) {
                String relative =
                        root.toURI().relativize(file.toURI()).getPath();

                if (relative.endsWith(".class")) {
                    addIndexedName(
                            names,
                            relative.substring(0, relative.length() - 6)
                                    .replace('/', '.')
                                    .replace('\\', '.'));
                }
            }
        }
    }

    private static void addIndexedName(Set<String> names, String className) {
        for (String root : ROOTS) {
            if (className.equals(root)
                    || className.startsWith(root + ".")) {
                names.add(className);
                return;
            }
        }
    }

    private static List<Field> allFields(Class<?> type) {
        ArrayList<Field> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Class<?> current = type;
                current != null;
                current = current.getSuperclass()) {

            for (Field field : current.getDeclaredFields()) {
                String key =
                        field.getName() + ":" + field.getType().getName();
                if (seen.add(key)) {
                    result.add(field);
                }
            }
        }

        return result;
    }

    private static List<Method> allMethods(Class<?> type) {
        ArrayList<Method> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Class<?> current = type;
                current != null;
                current = current.getSuperclass()) {

            for (Method method : current.getDeclaredMethods()) {
                String key = methodKey(method);
                if (seen.add(key)) {
                    result.add(method);
                }
            }
        }

        return result;
    }

    private static String methodKey(Method method) {
        StringBuilder key =
                new StringBuilder(method.getName()).append('(');

        for (Class<?> type : method.getParameterTypes()) {
            key.append(type.getName()).append(';');
        }

        return key.append(')').toString();
    }

    private static String valueString(Object value) {
        if (value == null) {
            return "null";
        }

        Class<?> type = value.getClass();
        if (!type.isArray()) {
            return String.valueOf(value);
        }

        int length = Array.getLength(value);
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                result.append(", ");
            }
            result.append(String.valueOf(Array.get(value, i)));
        }

        return result.append(']').toString();
    }

    private static List<String> tokenize(String text) {
        ArrayList<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }

            if (c == '\'' || c == '"') {
                quote = c;

            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }

            } else {
                current.append(c);
            }
        }

        if (escaped) {
            current.append('\\');
        }

        if (quote != 0) {
            throw new IllegalArgumentException("Unclosed quote");
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private static final class TargetRef {
        final Class<?> type;
        final Object instance;

        TargetRef(Class<?> type, Object instance) {
            this.type = type;
            this.instance = instance;
        }
    }
}
