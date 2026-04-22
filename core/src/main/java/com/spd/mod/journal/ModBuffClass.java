package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;

public class ModBuffClass {

    private static ArrayList<Class<? extends Buff>> cachedBuffs;

    public ModBuffClass() {
    }

    private static boolean isAllowedPackage(String pkg) {
        if (pkg.startsWith("com.shatteredpixel.shatteredpixeldungeon.actors.buffs.")) return true;
        if (pkg.startsWith("com.shatteredpixel.shatteredpixeldungeon.plants.")) return true;
        if (pkg.startsWith("com.shatteredpixel.shatteredpixeldungeon.items.potions.")) return true;
        if (pkg.startsWith("com.shatteredpixel.shatteredpixeldungeon.items.scrolls.")) return true;
        if (pkg.startsWith("com.shatteredpixel.shatteredpixeldungeon.items.stones.")) return true;
        if (pkg.startsWith("com.shatteredpixel.shatteredpixeldungeon.items.spells.")) return true;
        if (pkg.startsWith("com.shatteredpixel.shatteredpixeldungeon.items.food.")) return true;
        if (pkg.startsWith("com.shatteredpixel.shatteredpixeldungeon.items.bombs.")) return true;
        if (pkg.startsWith("com.shatteredpixel.shatteredpixeldungeon.items.wands.")) return true;
        if (pkg.startsWith("com.shatteredpixel.shatteredpixeldungeon.items.bags.")) return true;
        return false;
    }

    private static boolean isValidBuffClass(Class<?> clazz) {
        if (!Buff.class.isAssignableFrom(clazz)) return false;
        if (Buff.class.equals(clazz)) return false;
        if ((clazz.getModifiers() & 0x400) != 0) return false;
        if (clazz.isMemberClass()) {
            if ((clazz.getModifiers() & 0x8) == 0) return false;
        }
        return true;
    }

    private static boolean isBlacklistedClass(String className) {
        if (className.contains("PinCushion")) return true;
        if (className.contains("HTBoost")) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    public static ArrayList<Class<? extends Buff>> allBuffs() {
        if (cachedBuffs != null) {
            return cachedBuffs;
        }

        cachedBuffs = new ArrayList<>();

        try {
            // Android DEX 掃描邏輯
            ClassLoader classLoader = ModBuffClass.class.getClassLoader();
            Class<?> baseDexClassLoader = Class.forName("dalvik.system.BaseDexClassLoader");

            Field pathListField = baseDexClassLoader.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(classLoader);

            Field dexElementsField = pathList.getClass().getDeclaredField("dexElements");
            dexElementsField.setAccessible(true);
            Object[] dexElements = (Object[]) dexElementsField.get(pathList);

            for (Object element : dexElements) {
                Field dexFileField = element.getClass().getDeclaredField("dexFile");
                dexFileField.setAccessible(true);
                Object dexFile = dexFileField.get(element);

                if (dexFile != null) {
                    Method entriesMethod = dexFile.getClass().getMethod("entries");
                    Enumeration<String> entries = (Enumeration<String>) entriesMethod.invoke(dexFile);

                    while (entries.hasMoreElements()) {
                        String entry = entries.nextElement();
                        if (isAllowedPackage(entry) && !isBlacklistedClass(entry)) {
                            try {
                                Class<?> clazz = Class.forName(entry, false, classLoader);
                                if (isValidBuffClass(clazz)) {
                                    if (!cachedBuffs.contains(clazz)) {
                                        cachedBuffs.add((Class<? extends Buff>) clazz);
                                    }
                                }
                            } catch (Throwable ignore) {}
                        }
                    }
                }
            }
        } catch (Throwable e) {
            // JVM Desktop JAR 掃描邏輯
            try {
                String cp = System.getProperty("java.class.path");
                String[] paths = cp.split(System.getProperty("path.separator"));
                for (String path : paths) {
                    if (path.endsWith(".jar")) {
                        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(path);
                        Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                        while (entries.hasMoreElements()) {
                            String entry = entries.nextElement().getName();
                            if (entry.endsWith(".class")) {
                                String className = entry.replace('/', '.').substring(0, entry.length() - 6);
                                if (isAllowedPackage(className) && !isBlacklistedClass(className)) {
                                    try {
                                        Class<?> clazz = Class.forName(className);
                                        if (isValidBuffClass(clazz)) {
                                            if (!cachedBuffs.contains(clazz)) {
                                                cachedBuffs.add((Class<? extends Buff>) clazz);
                                            }
                                        }
                                    } catch (Throwable ignore) {}
                                }
                            }
                        }
                        zip.close();
                    }
                }
            } catch (Throwable ignore) {}
        }

        return cachedBuffs;
    }
}
