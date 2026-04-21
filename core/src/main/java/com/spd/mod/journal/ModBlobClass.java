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
            ClassLoader classLoader = ModBlobClass.class.getClassLoader();
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
                        
                        if (entry.startsWith("com.shatteredpixel.shatteredpixeldungeon.actors.blobs")) {
                            try {
                                Class<?> clazz = Class.forName(entry, false, classLoader);
                                
                                if (Blob.class.isAssignableFrom(clazz) && !Blob.class.equals(clazz)) {
                                    if ((clazz.getModifiers() & 0x400) == 0) {
                                        if (!clazz.isMemberClass() || (clazz.getModifiers() & 0x8) != 0) {
                                            cachedBlobs.add((Class<? extends Blob>) clazz);
                                        }
                                    }
                                }
                            } catch (Exception ignore) {
                            }
                        }
                    }
                }
            }
        } catch (Exception ignore) {
        }

        return cachedBlobs;
    }
}
