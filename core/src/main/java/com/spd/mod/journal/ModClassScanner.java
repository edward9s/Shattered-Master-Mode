package com.spd.mod.journal;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Discovers concrete SPD classes at runtime. The expensive DEX/JAR/directory
 * enumeration is performed once and cached; callers then filter that cache by
 * base type and package.
 */
public final class ModClassScanner {

    private static final String SPD_PACKAGE = "com.shatteredpixel.shatteredpixeldungeon.";
    private static final String SPD_PATH = "com/shatteredpixel/shatteredpixeldungeon/";

    private static ArrayList<String> cachedClassNames;

    private ModClassScanner() {
    }

    private static synchronized ArrayList<String> allClassNames() {
        if (cachedClassNames != null) {
            return cachedClassNames;
        }

        Set<String> names = new HashSet<>();
        ClassLoader classLoader = ModClassScanner.class.getClassLoader();

        scanAndroidDex(classLoader, names);
        scanDesktopClasspath(names);

        cachedClassNames = new ArrayList<>(names);
        Collections.sort(cachedClassNames);
        return cachedClassNames;
    }

    @SuppressWarnings("unchecked")
    public static <T> ArrayList<Class<? extends T>> subclassesOf(Class<T> baseClass, String... packagePrefixes) {
        ArrayList<Class<? extends T>> result = new ArrayList<>();
        ClassLoader classLoader = ModClassScanner.class.getClassLoader();

        for (String className : allClassNames()) {
            if (!matchesPackage(className, packagePrefixes)) {
                continue;
            }

            try {
                Class<?> clazz = Class.forName(className, false, classLoader);
                if (isConcreteSubclass(clazz, baseClass)) {
                    result.add((Class<? extends T>) clazz);
                }
            } catch (Throwable ignore) {
                // Some classes cannot be linked in every runtime/configuration.
            }
        }

        Collections.sort(result, new Comparator<Class<? extends T>>() {
            @Override
            public int compare(Class<? extends T> a, Class<? extends T> b) {
                return a.getName().compareTo(b.getName());
            }
        });
        return result;
    }

    private static boolean matchesPackage(String className, String... packagePrefixes) {
        if (packagePrefixes == null || packagePrefixes.length == 0) {
            return true;
        }
        for (String prefix : packagePrefixes) {
            if (prefix != null && className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConcreteSubclass(Class<?> clazz, Class<?> baseClass) {
        if (clazz == baseClass || !baseClass.isAssignableFrom(clazz)) {
            return false;
        }

        int modifiers = clazz.getModifiers();
        if (Modifier.isAbstract(modifiers) || Modifier.isInterface(modifiers)) {
            return false;
        }
        if (clazz.isAnonymousClass() || clazz.isLocalClass() || clazz.isSynthetic()) {
            return false;
        }
        if (clazz.isMemberClass() && !Modifier.isStatic(modifiers)) {
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static void scanAndroidDex(ClassLoader classLoader, Set<String> names) {
        try {
            Class<?> baseDexClassLoader = Class.forName("dalvik.system.BaseDexClassLoader");

            Field pathListField = baseDexClassLoader.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(classLoader);

            Field dexElementsField = pathList.getClass().getDeclaredField("dexElements");
            dexElementsField.setAccessible(true);
            Object[] dexElements = (Object[]) dexElementsField.get(pathList);

            for (Object element : dexElements) {
                try {
                    Field dexFileField = element.getClass().getDeclaredField("dexFile");
                    dexFileField.setAccessible(true);
                    Object dexFile = dexFileField.get(element);
                    if (dexFile == null) {
                        continue;
                    }

                    Method entriesMethod = dexFile.getClass().getMethod("entries");
                    Enumeration<String> entries = (Enumeration<String>) entriesMethod.invoke(dexFile);
                    while (entries.hasMoreElements()) {
                        addClassName(entries.nextElement(), names);
                    }
                } catch (Throwable ignore) {
                    // Continue with the remaining DEX elements.
                }
            }
        } catch (Throwable ignore) {
            // Not Android, or the runtime does not expose BaseDexClassLoader internals.
        }
    }

    private static void scanDesktopClasspath(Set<String> names) {
        String classPath = System.getProperty("java.class.path");
        if (classPath == null || classPath.length() == 0) {
            return;
        }

        String separator = System.getProperty("path.separator");
        if (separator == null || separator.length() == 0) {
            separator = File.pathSeparator;
        }

        for (String path : classPath.split(java.util.regex.Pattern.quote(separator))) {
            if (path == null || path.length() == 0) {
                continue;
            }

            File entry = new File(path);
            if (entry.isDirectory()) {
                File spdRoot = new File(entry, SPD_PATH);
                if (spdRoot.isDirectory()) {
                    scanDirectory(spdRoot, SPD_PACKAGE.substring(0, SPD_PACKAGE.length() - 1), names);
                }
            } else if (entry.isFile() && (path.endsWith(".jar") || path.endsWith(".zip"))) {
                scanArchive(entry, names);
            }
        }
    }

    private static void scanDirectory(File directory, String packageName, Set<String> names) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName(), names);
            } else if (file.getName().endsWith(".class")) {
                String simpleName = file.getName().substring(0, file.getName().length() - 6);
                addClassName(packageName + "." + simpleName, names);
            }
        }
    }

    private static void scanArchive(File archive, Set<String> names) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(archive);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String entryName = entries.nextElement().getName();
                if (entryName.startsWith(SPD_PATH) && entryName.endsWith(".class")) {
                    addClassName(entryName.replace('/', '.').substring(0, entryName.length() - 6), names);
                }
            }
        } catch (Throwable ignore) {
            // Ignore unreadable classpath entries.
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static void addClassName(String className, Set<String> names) {
        if (className != null && className.startsWith(SPD_PACKAGE)) {
            names.add(className);
        }
    }
}
