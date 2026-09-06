package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;

public class ModBlobClass {

    private static ArrayList<Class<? extends Blob>> cachedBlobs;

    public ModBlobClass() {
    }

    @SuppressWarnings("unchecked")
    public static ArrayList<Class<? extends Blob>> allBlobs() {
        if (cachedBlobs != null) {
            return cachedBlobs;
        }

        cachedBlobs = new ArrayList<>();

        try {
            // Android DEX 掃描邏輯
            ClassLoader classLoader = ModBlobClass.class.getClassLoader();
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

                    if (dexFile != null) {
                        Method entriesMethod = dexFile.getClass().getMethod("entries");
                        Enumeration<String> entries = (Enumeration<String>) entriesMethod.invoke(dexFile);

                        while (entries.hasMoreElements()) {
                            String entry = entries.nextElement();
                            if (entry.startsWith("com.shatteredpixel.shatteredpixeldungeon.actors.blobs")) {
                                try {
                                    Class<?> clazz = Class.forName(entry, false, classLoader);
                                    if (Blob.class.isAssignableFrom(clazz) && !Blob.class.equals(clazz)) {
                                        if ((clazz.getModifiers() & 0x400) == 0) {
                                            if (!clazz.isMemberClass() || (clazz.getModifiers() & 0x8) != 0) {
                                                if (!cachedBlobs.contains(clazz)) {
                                                    cachedBlobs.add((Class<? extends Blob>) clazz);
                                                }
                                            }
                                        }
                                    }
                                } catch (Throwable ignore) {
                                    // Some target classes cannot be linked in every runtime state.
                                }
                            }
                        }
                    }
                } catch (Throwable ignore) {
                    // Continue scanning the remaining dex elements.
                }
            }
        } catch (Throwable e) {
            // JVM Desktop JAR 掃描邏輯
            try {
                String cp = System.getProperty("java.class.path");
                String separator = System.getProperty("path.separator");
                if (cp == null || cp.length() == 0 || separator == null || separator.length() == 0) {
                    return cachedBlobs;
                }
                String[] paths = cp.split(java.util.regex.Pattern.quote(separator));
                for (String path : paths) {
                    if (path.endsWith(".jar")) {
                        java.util.zip.ZipFile zip = null;
                        try {
                            zip = new java.util.zip.ZipFile(path);
                            Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                            while (entries.hasMoreElements()) {
                                String entry = entries.nextElement().getName();
                                if (entry.endsWith(".class")) {
                                    String className = entry.replace('/', '.').substring(0, entry.length() - 6);
                                    if (className.startsWith("com.shatteredpixel.shatteredpixeldungeon.actors.blobs")) {
                                        try {
                                            Class<?> clazz = Class.forName(className, false, ModBlobClass.class.getClassLoader());
                                            if (Blob.class.isAssignableFrom(clazz) && !Blob.class.equals(clazz)) {
                                                if ((clazz.getModifiers() & 0x400) == 0) {
                                                    if (!clazz.isMemberClass() || (clazz.getModifiers() & 0x8) != 0) {
                                                        if (!cachedBlobs.contains(clazz)) {
                                                            cachedBlobs.add((Class<? extends Blob>) clazz);
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (Throwable ignore) {
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignore) {
                        } finally {
                            if (zip != null) {
                                try {
                                    zip.close();
                                } catch (Throwable ignore) {
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
        }

        return cachedBlobs;
    }
}
