package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight reflection-based numeric value search for the in-game debug
 * console. Search candidates are intentionally session-local and weakly
 * reference their owners so this tool never keeps discarded game objects alive.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
final class ModValueSearch {

    private static final int MAX_DEPTH = 8;
    private static final int MAX_OBJECTS = 20000;
    private static final int MAX_CANDIDATES = 10000;
    private static final int MAX_RESULT_LINES = 50;

    private static final String[] MODEL_PREFIXES = {
            "com.shatteredpixel.shatteredpixeldungeon.",
            "com.spd.mod."
    };

    private static final String[] PRESENTATION_PARTS = {
            ".ui.",
            ".windows.",
            ".scenes.",
            ".sprites.",
            ".effects.",
            ".tiles."
    };

    private static final LinkedHashMap<Long, Candidate> CANDIDATES =
            new LinkedHashMap<>();
    private static final LinkedHashSet<Long> ACTIVE =
            new LinkedHashSet<>();

    private static long nextId = 1;
    private static boolean sessionActive;

    private ModValueSearch() {
    }

    static void search(List<String> args) throws Exception {
        if (args.size() != 1) {
            throw new IllegalArgumentException(
                    "search <number|changed|unchanged|increased|decreased>");
        }

        String token = args.get(0).trim();
        String mode = token.toLowerCase(Locale.ROOT);

        if ("changed".equals(mode)
                || "unchanged".equals(mode)
                || "increased".equals(mode)
                || "decreased".equals(mode)) {

            if (!sessionActive) {
                throw new IllegalStateException(
                        "Start with search <number> before refining.");
            }
            refine(mode, null);
            return;
        }

        BigDecimal target = parseSearchNumber(token);
        if (!sessionActive) {
            initialSearch(target, token);
        } else {
            refine("exact", target);
        }
    }

    static void results(List<String> args) throws Exception {
        if (args.size() > 1) {
            throw new IllegalArgumentException("results [#id]");
        }
        requireSession();

        if (args.size() == 1) {
            showDetail(parseId(args.get(0)));
            return;
        }

        if (ACTIVE.isEmpty()) {
            GLog.i("Value search: 0 active results.");
            return;
        }

        StringBuilder out = new StringBuilder();
        out.append("Value search results: ").append(ACTIVE.size());

        int shown = 0;
        for (Long id : ACTIVE) {
            if (shown++ >= MAX_RESULT_LINES) {
                out.append("\n  ... ")
                        .append(ACTIVE.size() - MAX_RESULT_LINES)
                        .append(" more");
                break;
            }

            Candidate candidate = CANDIDATES.get(id);
            if (candidate == null) {
                continue;
            }

            out.append("\n#").append(candidate.id)
                    .append(' ')
                    .append(candidate.path)
                    .append(" = ");

            try {
                out.append(numberText(candidate.read()));
            } catch (StaleCandidateException stale) {
                out.append("<expired>");
            }
        }

        GLog.i(out.toString());
    }

    static void get(List<String> args) throws Exception {
        if (args.size() != 1) {
            throw new IllegalArgumentException("get #id");
        }
        requireSession();

        Candidate candidate = requireCandidate(parseId(args.get(0)));
        try {
            GLog.i(str(
                    "#", candidate.id, " ",
                    candidate.path, " = ",
                    numberText(candidate.read())));
        } catch (StaleCandidateException stale) {
            GLog.w(str("#", candidate.id, " ", candidate.path, " <expired>"));
        }
    }

    static void set(List<String> args) throws Exception {
        if (args.size() != 2) {
            throw new IllegalArgumentException("set #id <number>");
        }
        requireSession();

        Candidate candidate = requireCandidate(parseId(args.get(0)));
        if (!candidate.writable()) {
            throw new IllegalStateException(str(
                    "#", candidate.id, " is read-only: ", candidate.path));
        }

        Number before = candidate.read();
        Number value = parseForType(args.get(1), candidate.valueType);
        candidate.write(value);
        Number after = candidate.read();
        candidate.previous = after;

        GLog.p(str(
                "#", candidate.id, " ",
                candidate.path, ": ",
                numberText(before), " -> ",
                numberText(after)));
    }

    static void clear(List<String> args) {
        if (!args.isEmpty()) {
            throw new IllegalArgumentException("clear");
        }
        clearInternal();
        GLog.i("Value search cleared.");
    }

    private static void initialSearch(BigDecimal target, String raw)
            throws Exception {

        clearInternal();
        sessionActive = true;

        ScanState state = new ScanState(target);
        scanDungeonStatics(state);

        if (Dungeon.hero != null) {
            state.queue.addLast(new Node(Dungeon.hero, "hero", 0));
        }
        if (Dungeon.level != null) {
            state.queue.addLast(new Node(Dungeon.level, "level", 0));
        }

        while (!state.queue.isEmpty()
                && state.objects < MAX_OBJECTS
                && CANDIDATES.size() < MAX_CANDIDATES) {

            Node node = state.queue.removeFirst();
            if (node.value == null || node.depth > MAX_DEPTH) {
                continue;
            }
            if (state.visited.put(node.value, Boolean.TRUE) != null) {
                continue;
            }

            state.objects++;
            scanValue(node.value, node.path, node.depth, state);
        }

        boolean truncated =
                state.objects >= MAX_OBJECTS
                || CANDIDATES.size() >= MAX_CANDIDATES;

        GLog.h(str(
                "Search ", raw, ": ",
                ACTIVE.size(), " result(s), ",
                state.objects, " object(s) scanned",
                truncated ? " [limit reached]" : ""));
    }

    private static void refine(String mode, BigDecimal exact)
            throws Exception {

        int before = ACTIVE.size();
        LinkedHashSet<Long> kept = new LinkedHashSet<>();

        for (Long id : ACTIVE) {
            Candidate candidate = CANDIDATES.get(id);
            if (candidate == null) {
                continue;
            }

            Number current;
            try {
                current = candidate.read();
            } catch (StaleCandidateException stale) {
                continue;
            }

            boolean keep;
            if ("exact".equals(mode)) {
                keep = matches(current, exact);
            } else {
                int cmp = compare(current, candidate.previous);
                if ("changed".equals(mode)) {
                    keep = cmp != 0;
                } else if ("unchanged".equals(mode)) {
                    keep = cmp == 0;
                } else if ("increased".equals(mode)) {
                    keep = cmp > 0;
                } else if ("decreased".equals(mode)) {
                    keep = cmp < 0;
                } else {
                    throw new IllegalArgumentException(
                            "Unknown search refine mode: " + mode);
                }
            }

            if (keep) {
                candidate.previous = current;
                kept.add(id);
            }
        }

        ACTIVE.clear();
        ACTIVE.addAll(kept);

        GLog.h(str(
                "Value search ", mode, ": ",
                before, " -> ", ACTIVE.size(), " result(s)"));
    }

    private static void scanDungeonStatics(ScanState state) {
        Class<?> type = Dungeon.class;
        for (Field field : allFields(type)) {
            if (!Modifier.isStatic(field.getModifiers())
                    || field.isSynthetic()) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object value = field.get(null);

                if (value instanceof Number) {
                    if (!Modifier.isFinal(field.getModifiers())
                            && matches((Number) value, state.target)) {
                        addCandidate(new FieldCandidate(
                                nextId++,
                                str("Dungeon.", field.getName()),
                                numericType(field.getType(), (Number) value),
                                null,
                                field,
                                (Number) value));
                    }
                }
            } catch (Throwable ignored) {
                // A debug scan should skip inaccessible implementation fields.
            }
        }
    }

    private static void scanValue(
            Object value,
            String path,
            int depth,
            ScanState state) {

        Class<?> type = value.getClass();

        if (type.isArray()) {
            scanArray(value, path, depth, state);
            return;
        }

        if (value instanceof List) {
            scanList((List<?>) value, path, depth, state);
            return;
        }

        if (value instanceof Map) {
            scanMap((Map<?, ?>) value, path, depth, state);
            return;
        }

        if (isSparseArray(type)) {
            scanSparseArray(value, path, depth, state);
            return;
        }

        if (value instanceof Iterable) {
            scanIterable((Iterable<?>) value, path, depth, state);
            return;
        }

        if (!shouldReflect(type)) {
            return;
        }

        for (Field field : allFields(type)) {
            if (Modifier.isStatic(field.getModifiers())
                    || field.isSynthetic()) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object child = field.get(value);
                if (child == null) {
                    continue;
                }

                String childPath = str(path, ".", field.getName());

                if (child instanceof Number) {
                    if (!Modifier.isFinal(field.getModifiers())
                            && matches((Number) child, state.target)) {
                        addCandidate(new FieldCandidate(
                                nextId++,
                                childPath,
                                numericType(field.getType(), (Number) child),
                                value,
                                field,
                                (Number) child));
                    }
                } else if (!isLeaf(child.getClass())) {
                    enqueue(state, child, childPath, depth + 1);
                }

            } catch (Throwable ignored) {
                // Skip fields a target runtime refuses to expose.
            }

            if (CANDIDATES.size() >= MAX_CANDIDATES) {
                return;
            }
        }
    }

    private static void scanArray(
            Object array,
            String path,
            int depth,
            ScanState state) {

        int length = Array.getLength(array);
        Class<?> component = array.getClass().getComponentType();

        for (int i = 0; i < length; i++) {
            Object child = Array.get(array, i);
            if (child == null) {
                continue;
            }

            String childPath = str(path, "[", i, "]");

            if (child instanceof Number) {
                if (matches((Number) child, state.target)) {
                    addCandidate(new ArrayCandidate(
                            nextId++,
                            childPath,
                            numericType(component, (Number) child),
                            array,
                            i,
                            (Number) child));
                }
            } else if (!isLeaf(child.getClass())) {
                enqueue(state, child, childPath, depth + 1);
            }

            if (CANDIDATES.size() >= MAX_CANDIDATES) {
                return;
            }
        }
    }

    private static void scanList(
            List<?> list,
            String path,
            int depth,
            ScanState state) {

        int size;
        try {
            size = list.size();
        } catch (Throwable ignored) {
            return;
        }

        for (int i = 0; i < size; i++) {
            Object child;
            try {
                child = list.get(i);
            } catch (Throwable ignored) {
                continue;
            }
            if (child == null) {
                continue;
            }

            String childPath = str(path, "[", i, "]");

            if (child instanceof Number) {
                if (matches((Number) child, state.target)) {
                    addCandidate(new ListCandidate(
                            nextId++,
                            childPath,
                            numericType(child.getClass(), (Number) child),
                            list,
                            i,
                            (Number) child));
                }
            } else if (!isLeaf(child.getClass())) {
                enqueue(state, child, childPath, depth + 1);
            }

            if (CANDIDATES.size() >= MAX_CANDIDATES) {
                return;
            }
        }
    }

    private static void scanMap(
            Map<?, ?> map,
            String path,
            int depth,
            ScanState state) {

        int index = 0;
        try {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object child = entry.getValue();
                if (child != null
                        && !(child instanceof Number)
                        && !isLeaf(child.getClass())) {
                    enqueue(
                            state,
                            child,
                            str(path, "{", index, "}"),
                            depth + 1);
                }
                index++;
            }
        } catch (Throwable ignored) {
            // Concurrently changing collections are simply skipped this pass.
        }
    }

    private static void scanIterable(
            Iterable<?> iterable,
            String path,
            int depth,
            ScanState state) {

        int index = 0;
        try {
            Iterator<?> iterator = iterable.iterator();
            while (iterator.hasNext()) {
                Object child = iterator.next();
                if (child != null
                        && !(child instanceof Number)
                        && !isLeaf(child.getClass())) {
                    enqueue(
                            state,
                            child,
                            str(path, "[", index, "]"),
                            depth + 1);
                }
                index++;
            }
        } catch (Throwable ignored) {
            // A changing collection should not abort the whole search.
        }
    }

    private static void scanSparseArray(
            Object sparse,
            String path,
            int depth,
            ScanState state) {

        try {
            Method valueList = sparse.getClass().getMethod("valueList");
            Object values = valueList.invoke(sparse);
            if (values instanceof List) {
                scanList((List<?>) values, path, depth, state);
            }
        } catch (Throwable ignored) {
            // SparseArray support is opportunistic across SPD forks.
        }
    }

    private static void enqueue(
            ScanState state,
            Object value,
            String path,
            int depth) {

        if (value == null || depth > MAX_DEPTH || isLeaf(value.getClass())) {
            return;
        }
        state.queue.addLast(new Node(value, path, depth));
    }

    private static boolean shouldReflect(Class<?> type) {
        String name = type.getName();

        boolean model = false;
        for (String prefix : MODEL_PREFIXES) {
            if (name.startsWith(prefix)) {
                model = true;
                break;
            }
        }
        if (!model) {
            return false;
        }

        if (name.startsWith("com.spd.mod.mechanics.ModDebug")
                || name.startsWith("com.spd.mod.mechanics.ModValueSearch")) {
            return false;
        }

        for (String part : PRESENTATION_PARTS) {
            if (name.contains(part)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isLeaf(Class<?> type) {
        return type == String.class
                || Number.class.isAssignableFrom(type)
                || type == Boolean.class
                || type == Character.class
                || type.isEnum()
                || type == Class.class
                || ClassLoader.class.isAssignableFrom(type);
    }

    private static boolean isSparseArray(Class<?> type) {
        return "com.watabou.utils.SparseArray".equals(type.getName());
    }

    private static List<Field> allFields(Class<?> type) {
        ArrayList<Field> result = new ArrayList<>();
        for (Class<?> current = type;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field field : fields) {
                result.add(field);
            }
        }
        return result;
    }

    private static void addCandidate(Candidate candidate) {
        CANDIDATES.put(candidate.id, candidate);
        ACTIVE.add(candidate.id);
    }

    private static void showDetail(long id) throws Exception {
        Candidate candidate = requireCandidate(id);

        String current;
        try {
            current = numberText(candidate.read());
        } catch (StaleCandidateException stale) {
            current = "<expired>";
        }

        GLog.i(str(
                "#", candidate.id,
                "\nPath: ", candidate.path,
                "\nType: ", candidate.valueType.getSimpleName(),
                "\nCurrent: ", current,
                "\nPrevious: ", numberText(candidate.previous),
                "\nStatus: ", ACTIVE.contains(candidate.id)
                        ? "active" : "filtered",
                "\nWritable: ", candidate.writable()));
    }

    private static Candidate requireCandidate(long id) {
        Candidate candidate = CANDIDATES.get(id);
        if (candidate == null) {
            throw new IllegalArgumentException(str(
                    "Unknown value-search id: #", id));
        }
        return candidate;
    }

    private static void requireSession() {
        if (!sessionActive) {
            throw new IllegalStateException(
                    "No value-search session. Start with search <number>.");
        }
    }

    private static long parseId(String token) {
        String raw = token.trim();
        if (raw.startsWith("#")) {
            raw = raw.substring(1);
        }
        try {
            long id = Long.parseLong(raw);
            if (id < 1) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(str(
                    "Invalid result id: ", token));
        }
    }

    private static BigDecimal parseSearchNumber(String raw) {
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(str(
                    "Invalid search number: ", raw));
        }
    }

    private static boolean matches(Number value, BigDecimal target) {
        BigDecimal decimal = decimal(value);
        return decimal != null && decimal.compareTo(target) == 0;
    }

    private static int compare(Number a, Number b) {
        BigDecimal left = decimal(a);
        BigDecimal right = decimal(b);

        if (left != null && right != null) {
            return left.compareTo(right);
        }

        return Double.compare(a.doubleValue(), b.doubleValue());
    }

    private static BigDecimal decimal(Number value) {
        if (value instanceof Double) {
            double number = value.doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                return null;
            }
        } else if (value instanceof Float) {
            float number = value.floatValue();
            if (Float.isNaN(number) || Float.isInfinite(number)) {
                return null;
            }
        }

        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static Class<?> numericType(Class<?> declared, Number value) {
        if (isSupportedNumericType(declared)) {
            return declared;
        }
        Class<?> actual = value.getClass();
        return isSupportedNumericType(actual) ? actual : Double.class;
    }

    private static boolean isSupportedNumericType(Class<?> type) {
        return type == byte.class || type == Byte.class
                || type == short.class || type == Short.class
                || type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == float.class || type == Float.class
                || type == double.class || type == Double.class;
    }

    private static Number parseForType(String raw, Class<?> type) {
        try {
            if (type == byte.class || type == Byte.class) {
                return Byte.valueOf(exactInteger(raw).byteValueExact());
            }
            if (type == short.class || type == Short.class) {
                return Short.valueOf(exactInteger(raw).shortValueExact());
            }
            if (type == int.class || type == Integer.class) {
                return Integer.valueOf(exactInteger(raw).intValueExact());
            }
            if (type == long.class || type == Long.class) {
                return Long.valueOf(exactInteger(raw).longValueExact());
            }
            if (type == float.class || type == Float.class) {
                float value = Float.parseFloat(raw);
                if (Float.isNaN(value) || Float.isInfinite(value)) {
                    throw new NumberFormatException();
                }
                return Float.valueOf(value);
            }
            if (type == double.class || type == Double.class) {
                double value = Double.parseDouble(raw);
                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    throw new NumberFormatException();
                }
                return Double.valueOf(value);
            }
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException(str(
                    "Value ", raw, " is out of range for ",
                    type.getSimpleName()));
        }

        throw new IllegalArgumentException(str(
                "Unsupported numeric type: ", type.getName()));
    }

    private static BigInteger exactInteger(String raw) {
        return new BigDecimal(raw).toBigIntegerExact();
    }

    private static String numberText(Number value) {
        return value == null ? "<null>" : String.valueOf(value);
    }

    private static void clearInternal() {
        CANDIDATES.clear();
        ACTIVE.clear();
        nextId = 1;
        sessionActive = false;
    }

    private static String str(Object... parts) {
        StringBuilder result = new StringBuilder();
        for (Object part : parts) {
            result.append(String.valueOf(part));
        }
        return result.toString();
    }

    private static final class ScanState {
        final BigDecimal target;
        final ArrayDeque<Node> queue = new ArrayDeque<>();
        final IdentityHashMap<Object, Boolean> visited =
                new IdentityHashMap<>();
        int objects;

        ScanState(BigDecimal target) {
            this.target = target;
        }
    }

    private static final class Node {
        final Object value;
        final String path;
        final int depth;

        Node(Object value, String path, int depth) {
            this.value = value;
            this.path = path;
            this.depth = depth;
        }
    }

    private abstract static class Candidate {
        final long id;
        final String path;
        final Class<?> valueType;
        Number previous;

        Candidate(
                long id,
                String path,
                Class<?> valueType,
                Number previous) {
            this.id = id;
            this.path = path;
            this.valueType = valueType;
            this.previous = previous;
        }

        abstract Number read() throws Exception;

        abstract void write(Number value) throws Exception;

        abstract boolean writable();
    }

    private static final class FieldCandidate extends Candidate {
        final WeakReference<Object> owner;
        final Field field;
        final boolean isStatic;

        FieldCandidate(
                long id,
                String path,
                Class<?> valueType,
                Object owner,
                Field field,
                Number previous) {

            super(id, path, valueType, previous);
            this.isStatic = Modifier.isStatic(field.getModifiers());
            this.owner = isStatic
                    ? null
                    : new WeakReference<Object>(owner);
            this.field = field;
        }

        private Object owner() {
            if (isStatic) {
                return null;
            }
            Object value = owner.get();
            if (value == null) {
                throw new StaleCandidateException();
            }
            return value;
        }

        @Override
        Number read() throws Exception {
            field.setAccessible(true);
            Object value = field.get(owner());
            if (!(value instanceof Number)) {
                throw new StaleCandidateException();
            }
            return (Number) value;
        }

        @Override
        void write(Number value) throws Exception {
            if (!writable()) {
                throw new IllegalStateException("Field is final");
            }
            field.setAccessible(true);
            field.set(owner(), value);
        }

        @Override
        boolean writable() {
            return !Modifier.isFinal(field.getModifiers());
        }
    }

    private static final class ArrayCandidate extends Candidate {
        final WeakReference<Object> array;
        final int index;

        ArrayCandidate(
                long id,
                String path,
                Class<?> valueType,
                Object array,
                int index,
                Number previous) {

            super(id, path, valueType, previous);
            this.array = new WeakReference<Object>(array);
            this.index = index;
        }

        private Object array() {
            Object value = array.get();
            if (value == null || index >= Array.getLength(value)) {
                throw new StaleCandidateException();
            }
            return value;
        }

        @Override
        Number read() {
            Object value = Array.get(array(), index);
            if (!(value instanceof Number)) {
                throw new StaleCandidateException();
            }
            return (Number) value;
        }

        @Override
        void write(Number value) {
            Array.set(array(), index, value);
        }

        @Override
        boolean writable() {
            return true;
        }
    }

    private static final class ListCandidate extends Candidate {
        final WeakReference<List<?>> list;
        final int index;

        ListCandidate(
                long id,
                String path,
                Class<?> valueType,
                List<?> list,
                int index,
                Number previous) {

            super(id, path, valueType, previous);
            this.list = new WeakReference<List<?>>(list);
            this.index = index;
        }

        private List<?> list() {
            List<?> value = list.get();
            if (value == null || index >= value.size()) {
                throw new StaleCandidateException();
            }
            return value;
        }

        @Override
        Number read() {
            Object value = list().get(index);
            if (!(value instanceof Number)) {
                throw new StaleCandidateException();
            }
            return (Number) value;
        }

        @Override
        void write(Number value) {
            ((List) list()).set(index, value);
        }

        @Override
        boolean writable() {
            return true;
        }
    }

    private static final class StaleCandidateException
            extends IllegalStateException {
        StaleCandidateException() {
            super("Candidate owner no longer exists");
        }
    }
}
