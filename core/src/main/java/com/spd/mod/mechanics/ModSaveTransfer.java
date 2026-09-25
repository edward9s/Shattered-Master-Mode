package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.Game;
import com.watabou.utils.DeviceCompat;
import com.watabou.utils.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Shared save-file transfer core used by the Tools window and ModDebug.
 *
 * <p>Android keeps SMM's existing one-click full-snapshot behavior. Desktop
 * uses a native folder chooser; the selected directory is the complete
 * snapshot source or destination.</p>
 */
public final class ModSaveTransfer {

    private static final String PREF_EXPORT_DIRECTORY =
            "desktop_export_directory";
    private static final String PREF_IMPORT_DIRECTORY =
            "desktop_import_directory";

    private ModSaveTransfer() {
    }

    public static void exportSave() throws Exception {
        if (DeviceCompat.isDesktop()) {
            runDesktopTransferLater(true);
            return;
        }

        if (!DeviceCompat.isAndroid()) {
            throw new UnsupportedOperationException(
                    "save export is not supported on this platform");
        }

        exportAndroidSnapshot();
    }

    public static void importSave() throws Exception {
        if (DeviceCompat.isDesktop()) {
            runDesktopTransferLater(false);
            return;
        }

        if (!DeviceCompat.isAndroid()) {
            throw new UnsupportedOperationException(
                    "save import is not supported on this platform");
        }

        importAndroidSnapshot();
    }

    private static void runDesktopTransferLater(final boolean export) {
        // Button clicks run while PointerEvent is iterating its event queue.
        // Opening a native desktop dialog synchronously can enqueue focus/pointer
        // events into that same list and trigger ConcurrentModificationException.
        // postRunnable runs after the current input dispatch has returned.
        Game.runOnRenderThread(() -> {
            try {
                if (export) {
                    if (exportDesktopSnapshot()) {
                        System.out.println("SPD_Mod: Save exported!");
                    }
                } else {
                    importDesktopSnapshot();
                }
            } catch (Exception e) {
                System.out.println(
                        "SPD_Mod: "
                                + (export ? "Export" : "Import")
                                + " Crash - "
                                + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private static void exportAndroidSnapshot() throws Exception {
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
            androidDeleteContents(targetDir);
        } else if (!targetDir.mkdirs() && !targetDir.isDirectory()) {
            throw new IOException(
                    "Unable to create export directory: "
                            + targetDir.getAbsolutePath());
        }

        androidCopyRecursively(sourceDir, targetDir, false);

        System.out.println("SPD_Mod: === EXPORT FINISHED ===");
        GLog.h("Save exported!", new Object[0]);
    }

    private static void importAndroidSnapshot() throws Exception {
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
        androidDeleteContents(targetDir);

        // Best-effort fsync each imported file before the process is killed.
        androidCopyRecursively(sourceDir, targetDir, true);

        System.out.println("SPD_Mod: === IMPORT DONE, KILLING ===");
        Class<?> processClass = Class.forName("android.os.Process");
        int pid = ((Integer) processClass
                .getMethod("myPid")
                .invoke(null)).intValue();
        processClass
                .getMethod("killProcess", int.class)
                .invoke(null, pid);
    }

    private static boolean exportDesktopSnapshot() throws Exception {
        File sourceDir = desktopSaveDirectory();
        File targetDir = chooseDesktopDirectory(
                "Export Save",
                PREF_EXPORT_DIRECTORY);
        if (targetDir == null) {
            return false;
        }

        if (directoriesOverlap(sourceDir, targetDir)) {
            throw new IOException(
                    "Selected export directory overlaps the active save directory");
        }

        if (desktopListFiles(targetDir).length > 0
                && !looksLikeSpdSaveDirectory(targetDir)) {
            throw new IOException(
                    "Selected export directory does not look like SPD save data: "
                            + targetDir.getAbsolutePath());
        }

        Dungeon.saveAll();
        desktopDeleteContents(targetDir);
        desktopCopyRecursively(sourceDir, targetDir, false);
        return true;
    }

    private static void importDesktopSnapshot() throws Exception {
        File sourceDir = chooseDesktopDirectory(
                "Import Save",
                PREF_IMPORT_DIRECTORY);
        if (sourceDir == null) {
            return;
        }

        File targetDir = desktopSaveDirectory();
        if (directoriesOverlap(sourceDir, targetDir)) {
            throw new IOException(
                    "Selected import directory overlaps the active save directory");
        }

        if (desktopListFiles(sourceDir).length == 0) {
            System.out.println("SPD_Mod: No save to import!");
            return;
        }

        desktopDeleteContents(targetDir);
        desktopCopyRecursively(sourceDir, targetDir, true);

        // Imported settings and saves are now on disk while this process still
        // holds the old state in memory. Exit instead of mixing the two states.
        System.exit(0);
    }

    private static File desktopSaveDirectory() throws IOException {
        File directory = FileUtils.getFileHandle("").file().getCanonicalFile();
        if (!directory.exists() || !directory.isDirectory()) {
            throw new IOException(
                    "Desktop save directory is unavailable: "
                            + directory.getAbsolutePath());
        }
        return directory;
    }

    private static File chooseDesktopDirectory(
            String title,
            String preferenceKey) throws Exception {

        String defaultPath = desktopPreferenceGet(
                preferenceKey,
                System.getProperty("user.home", "."));
        File defaultDirectory = new File(defaultPath);
        if (!defaultDirectory.exists() || !defaultDirectory.isDirectory()) {
            defaultDirectory = new File(System.getProperty("user.home", "."));
        }

        Class<?> dialogs = Class.forName("org.lwjgl.util.tinyfd.TinyFileDialogs");
        Object selected = dialogs
                .getMethod(
                        "tinyfd_selectFolderDialog",
                        CharSequence.class,
                        CharSequence.class)
                .invoke(
                        null,
                        title,
                        defaultDirectory.getAbsolutePath());

        if (selected == null || selected.toString().isEmpty()) {
            return null;
        }

        File directory = new File(selected.toString()).getCanonicalFile();
        if (!directory.exists() || !directory.isDirectory()) {
            throw new IOException(
                    "Selected path is not a directory: "
                            + directory.getAbsolutePath());
        }

        desktopPreferencePut(preferenceKey, directory.getAbsolutePath());
        return directory;
    }

    private static String desktopPreferenceGet(
            String key,
            String defaultValue) throws Exception {

        Class<?> preferencesClass =
                Class.forName("java.util.prefs.Preferences");
        Object preferences = desktopPreferences(preferencesClass);
        return (String) preferencesClass
                .getMethod("get", String.class, String.class)
                .invoke(
                        preferences,
                        desktopPreferenceKey(key),
                        defaultValue);
    }

    private static void desktopPreferencePut(String key, String value)
            throws Exception {

        Class<?> preferencesClass =
                Class.forName("java.util.prefs.Preferences");
        Object preferences = desktopPreferences(preferencesClass);
        preferencesClass
                .getMethod("put", String.class, String.class)
                .invoke(
                        preferences,
                        desktopPreferenceKey(key),
                        value);
        preferencesClass
                .getMethod("flush")
                .invoke(preferences);
    }

    private static Object desktopPreferences(Class<?> preferencesClass)
            throws Exception {

        return preferencesClass
                .getMethod("userNodeForPackage", Class.class)
                .invoke(null, ModSaveTransfer.class);
    }

    private static String desktopPreferenceKey(String key)
            throws IOException {

        String savePath = desktopSaveDirectory().getAbsolutePath();
        String identity = UUID.nameUUIDFromBytes(
                savePath.getBytes(StandardCharsets.UTF_8)).toString();
        return key + "." + identity;
    }

    private static File[] desktopListFiles(File directory)
            throws IOException {

        if (directory == null
                || !directory.exists()
                || !directory.isDirectory()) {
            return new File[0];
        }

        File[] files = directory.listFiles();
        if (files == null) {
            throw new IOException(
                    "Unable to list directory: "
                            + directory.getAbsolutePath());
        }
        return files;
    }

    private static boolean looksLikeSpdSaveDirectory(File directory)
            throws IOException {

        // settings.xml is intentionally not sufficient by itself: many libGDX
        // applications use that generic name. These are SPD-specific save
        // artifacts that are stable across Shattered-derived forks.
        if (new File(directory, "rankings.dat").isFile()
                || new File(directory, "badges.dat").isFile()
                || new File(directory, "journal.dat").isFile()) {
            return true;
        }

        for (File file : desktopListFiles(directory)) {
            if (!file.isDirectory()
                    || !file.getName().matches("game\\d+")) {
                continue;
            }
            if (new File(file, "game.dat").isFile()) {
                return true;
            }
        }

        return false;
    }

    private static boolean directoriesOverlap(File first, File second)
            throws IOException {

        File firstCanonical = first.getCanonicalFile();
        File secondCanonical = second.getCanonicalFile();
        return containsDirectory(firstCanonical, secondCanonical)
                || containsDirectory(secondCanonical, firstCanonical);
    }

    private static boolean containsDirectory(File parent, File child) {
        File current = child;
        while (current != null) {
            if (parent.equals(current)) {
                return true;
            }
            current = current.getParentFile();
        }
        return false;
    }

    private static void desktopDeleteContents(File directory)
            throws IOException {

        if (directory == null
                || !directory.exists()
                || !directory.isDirectory()) {
            return;
        }

        File[] files = desktopListFiles(directory);
        for (File file : files) {
            desktopDeleteRecursively(file);
        }
    }

    private static void desktopDeleteRecursively(File file)
            throws IOException {

        if (file == null || !file.exists()) {
            return;
        }

        if (file.isDirectory()) {
            File[] children = desktopListFiles(file);
            for (File child : children) {
                desktopDeleteRecursively(child);
            }
        }

        if (!file.delete() && file.exists()) {
            throw new IOException(
                    "Unable to delete: " + file.getAbsolutePath());
        }
    }

    private static void desktopCopyRecursively(
            File source,
            File target,
            boolean syncFiles) throws IOException {

        if (!source.exists()) {
            throw new IOException(
                    "Copy source disappeared: "
                            + source.getAbsolutePath());
        }

        if (source.isDirectory()) {
            if (target.exists()
                    && target.isFile()
                    && !target.delete()) {
                throw new IOException(
                        "Unable to replace file with directory: "
                                + target.getAbsolutePath());
            }
            if (!target.exists()
                    && !target.mkdirs()
                    && !target.isDirectory()) {
                throw new IOException(
                        "Unable to create directory: "
                                + target.getAbsolutePath());
            }

            File[] files = desktopListFiles(source);
            for (File file : files) {
                desktopCopyRecursively(
                        file,
                        new File(target, file.getName()),
                        syncFiles);
            }
            return;
        }

        copyFile(source, target, syncFiles, 8192);
    }

    private static void androidDeleteContents(File directory) {
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
            androidDeleteRecursively(file);
        }
    }

    private static void androidDeleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    androidDeleteRecursively(child);
                }
            }
        }

        boolean deleted = file.delete();
        System.out.println(
                "SPD_Mod: "
                        + (deleted ? "[DEL OK] " : "[DEL FAIL] ")
                        + file.getAbsolutePath());
    }

    private static void androidCopyRecursively(
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
                androidCopyRecursively(
                        file,
                        new File(target, file.getName()),
                        syncFiles);
            }
            return;
        }

        if (!syncFiles) {
            System.out.println(
                    "SPD_Mod: Exp: "
                            + source.getName()
                            + " > "
                            + target.getAbsolutePath());
        }

        copyFile(source, target, syncFiles, 1024);
    }

    private static void copyFile(
            File source,
            File target,
            boolean syncFiles,
            int bufferSize) throws IOException {

        File parent = target.getParentFile();
        if (parent != null
                && !parent.exists()
                && !parent.mkdirs()
                && !parent.isDirectory()) {
            throw new IOException(
                    "Unable to create directory: "
                            + parent.getAbsolutePath());
        }

        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {

            byte[] buffer = new byte[bufferSize];
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
        }
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
}
