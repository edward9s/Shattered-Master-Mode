package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Shared Android save-file transfer core used by both the Tools window and
 * ModDebug. The operation ordering here is part of the save/import safety
 * contract and must not be duplicated in entry-point UI code.
 */
public final class ModSaveTransfer {

    private ModSaveTransfer() {
    }

    public static void exportSave() throws Exception {
        System.out.println("SPD_Mod: === EXPORT START ===");

        Object context = androidContext();
        if (!ensureAllFilesAccess(context)) {
            return;
        }

        // Flush the live run before replacing the external snapshot.
        Dungeon.saveAll();

        File sourceDir = (File) context.getClass()
                .getMethod("getFilesDir")
                .invoke(context);
        String packageName = (String) context.getClass()
                .getMethod("getPackageName")
                .invoke(context);
        File targetDir = new File("/sdcard/Download/" + packageName);

        System.out.println("SPD_Mod: Source: " + sourceDir.getAbsolutePath());

        // Export is a complete snapshot. Old external files must not survive.
        if (targetDir.exists()) {
            if (!targetDir.isDirectory()) {
                throw new IOException(
                        "Export path is not a directory: "
                                + targetDir.getAbsolutePath());
            }
            System.out.println(
                    "SPD_Mod: Clearing external folder: "
                            + targetDir.getAbsolutePath());
            deleteContents(targetDir);
        } else if (!targetDir.mkdirs() && !targetDir.isDirectory()) {
            throw new IOException(
                    "Unable to create export directory: "
                            + targetDir.getAbsolutePath());
        }

        copyRecursively(sourceDir, targetDir, false);

        System.out.println("SPD_Mod: === EXPORT FINISHED ===");
        GLog.h("Save exported!", new Object[0]);
    }

    public static void importSave() throws Exception {
        System.out.println("SPD_Mod: === IMPORT START ===");

        Object context = androidContext();
        if (!ensureAllFilesAccess(context)) {
            return;
        }

        String packageName = (String) context.getClass()
                .getMethod("getPackageName")
                .invoke(context);
        File sourceDir = new File("/sdcard/Download/" + packageName);
        File targetDir = (File) context.getClass()
                .getMethod("getFilesDir")
                .invoke(context);

        // Critical invariant: validate a real, non-empty external snapshot
        // before deleting any live app files.
        File[] sourceFiles = sourceDir.listFiles();
        if (!sourceDir.exists()
                || !sourceDir.isDirectory()
                || sourceFiles == null
                || sourceFiles.length == 0) {
            System.out.println(
                    "SPD_Mod: Import aborted - no valid save at "
                            + sourceDir.getAbsolutePath());
            GLog.w("No save to import!", new Object[0]);
            return;
        }

        // Keep filesDir itself, but remove all previous contents.
        System.out.println(
                "SPD_Mod: Clearing local save folder: "
                        + targetDir.getAbsolutePath());
        deleteContents(targetDir);

        // Best-effort fsync each imported file before the process is killed.
        copyRecursively(sourceDir, targetDir, true);

        System.out.println("SPD_Mod: === IMPORT DONE, KILLING ===");
        Class<?> processClass = Class.forName("android.os.Process");
        int pid = ((Integer) processClass
                .getMethod("myPid")
                .invoke(null)).intValue();
        processClass
                .getMethod("killProcess", int.class)
                .invoke(null, pid);
    }

    private static Object androidContext() throws Exception {
        try {
            Class<?> activityThread =
                    Class.forName("android.app.ActivityThread");
            Object application = activityThread
                    .getMethod("currentApplication")
                    .invoke(null);
            if (application == null) {
                throw new IllegalStateException(
                        "Android application context is unavailable");
            }
            return application;
        } catch (ClassNotFoundException notAndroid) {
            throw new UnsupportedOperationException(
                    "save/load are Android-only");
        }
    }

    private static boolean ensureAllFilesAccess(Object context)
            throws Exception {

        Class<?> buildVersionClass =
                Class.forName("android.os.Build$VERSION");
        int sdkInt = buildVersionClass
                .getField("SDK_INT")
                .getInt(null);

        if (sdkInt < 30) {
            return true;
        }

        Class<?> environmentClass =
                Class.forName("android.os.Environment");
        boolean manager = ((Boolean) environmentClass
                .getMethod("isExternalStorageManager")
                .invoke(null)).booleanValue();

        if (manager) {
            return true;
        }

        Class<?> intentClass = Class.forName("android.content.Intent");
        Object intent = intentClass
                .getConstructor(String.class)
                .newInstance(
                        "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");

        Class<?> uriClass = Class.forName("android.net.Uri");
        String packageName = (String) context.getClass()
                .getMethod("getPackageName")
                .invoke(context);
        Object uri = uriClass
                .getMethod(
                        "fromParts",
                        String.class,
                        String.class,
                        String.class)
                .invoke(null, "package", packageName, null);

        intentClass.getMethod("setData", uriClass).invoke(intent, uri);
        intentClass.getMethod("addFlags", int.class)
                .invoke(intent, 0x10000000);
        context.getClass()
                .getMethod("startActivity", intentClass)
                .invoke(context, intent);

        GLog.w(
                "Grant All files access, return to the game, then try again.",
                new Object[0]);
        return false;
    }

    private static void deleteContents(File directory) {
        if (directory == null
                || !directory.exists()
                || !directory.isDirectory()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            deleteRecursively(file);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }

        boolean deleted = file.delete();
        System.out.println(
                "SPD_Mod: "
                        + (deleted ? "[DEL OK] " : "[DEL FAIL] ")
                        + file.getAbsolutePath());
    }

    private static void copyRecursively(
            File source,
            File target,
            boolean syncFiles) throws IOException {

        if (!source.exists()) {
            return;
        }

        if (source.isDirectory()) {
            if (target.exists() && target.isFile()) {
                if (!target.delete()) {
                    throw new IOException(
                            "Unable to replace file with directory: "
                                    + target.getAbsolutePath());
                }
            }
            if (!target.exists()
                    && !target.mkdirs()
                    && !target.isDirectory()) {
                throw new IOException(
                        "Unable to create directory: "
                                + target.getAbsolutePath());
            }

            File[] files = source.listFiles();
            if (files == null) {
                return;
            }

            for (File file : files) {
                System.out.println(
                        "SPD_Mod: "
                                + (file.isDirectory() ? "[DIR]  " : "[FILE] ")
                                + file.getName());
                copyRecursively(
                        file,
                        new File(target, file.getName()),
                        syncFiles);
            }
            return;
        }

        File parent = target.getParentFile();
        if (parent != null
                && !parent.exists()
                && !parent.mkdirs()
                && !parent.isDirectory()) {
            throw new IOException(
                    "Unable to create directory: "
                            + parent.getAbsolutePath());
        }

        if (!syncFiles) {
            System.out.println(
                    "SPD_Mod: Exp: "
                            + source.getName()
                            + " > "
                            + target.getAbsolutePath());
        }

        FileInputStream input = new FileInputStream(source);
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(target);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }

            output.flush();
            if (syncFiles) {
                try {
                    output.getFD().sync();
                } catch (Exception ignored) {
                    // Existing import behavior treats fsync as best effort.
                }
            }
        } finally {
            try {
                input.close();
            } finally {
                if (output != null) {
                    output.close();
                }
            }
        }
    }
}
