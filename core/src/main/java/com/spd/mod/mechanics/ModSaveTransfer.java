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
 * Shared save-file transfer core used by both the Tools window and ModDebug.
 *
 * <p>Android keeps SMM's existing one-click full-snapshot behavior. Desktop
 * uses a native folder chooser and only replaces folders explicitly marked as
 * SMM exports.</p>
 */
public final class ModSaveTransfer {

    private static final String DESKTOP_MARKER = ".smm-save-transfer";
    private static final byte[] DESKTOP_MARKER_CONTENT =
            "SMM save transfer v1\n".getBytes(StandardCharsets.UTF_8);

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
                    exportDesktopSnapshot();
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
                GLog.w(
                        export ? "Export failed!" : "Import failed!",
                        new Object[0]);
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
        File targetDir = androidExternalSaveDirectory(context);

        System.out.println("SPD_Mod: Source: " + sourceDir.getAbsolutePath());

        replaceSnapshot(sourceDir, targetDir, false);

        System.out.println("SPD_Mod: === EXPORT FINISHED ===");
        GLog.h("Save exported!", new Object[0]);
    }

    private static void importAndroidSnapshot() throws Exception {
        System.out.println("SPD_Mod: === IMPORT START ===");

        Object context = androidContext();
        if (!ensureAllFilesAccess(context)) {
            return;
        }

        File sourceDir = androidExternalSaveDirectory(context);
        File targetDir = (File) context.getClass()
                .getMethod("getFilesDir")
                .invoke(context);

        // Critical invariant: validate a real, non-empty external snapshot
        // before deleting any live app files.
        if (!hasAnyContent(sourceDir)) {
            System.out.println(
                    "SPD_Mod: Import aborted - no valid save at "
                            + sourceDir.getAbsolutePath());
            GLog.w("No save to import!", new Object[0]);
            return;
        }

        System.out.println(
                "SPD_Mod: Clearing local save folder: "
                        + targetDir.getAbsolutePath());
        deleteContents(targetDir);
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

    private static void exportDesktopSnapshot() throws Exception {
        File sourceDir = desktopSaveDirectory();
        File targetDir = chooseDesktopDirectory(
                "Export Save",
                PREF_EXPORT_DIRECTORY);
        if (targetDir == null) {
            return;
        }

        if (directoriesOverlap(sourceDir, targetDir)) {
            throw new IOException(
                    "Selected export directory overlaps the active save directory");
        }

        File[] targetFiles = listFiles(targetDir);
        File marker = new File(targetDir, DESKTOP_MARKER);
        if (targetFiles.length > 0 && !marker.isFile()) {
            throw new IOException(
                    "Desktop export directory is not empty and is not a previous SMM export");
        }

        Dungeon.saveAll();
        deleteContents(targetDir);
        copyRecursively(sourceDir, targetDir, false);
        writeDesktopMarker(targetDir);

        GLog.h("Save exported!", new Object[0]);
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

        if (!validDesktopSnapshot(sourceDir)) {
            GLog.w("No save to import!", new Object[0]);
            return;
        }

        deleteContents(targetDir);
        copyDesktopSnapshotContents(sourceDir, targetDir, true);

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

    private static boolean validDesktopSnapshot(File sourceDir)
            throws IOException {

        File marker = new File(sourceDir, DESKTOP_MARKER);
        if (!marker.isFile()) {
            return false;
        }

        File[] files = listFiles(sourceDir);
        for (File file : files) {
            if (!DESKTOP_MARKER.equals(file.getName())) {
                return true;
            }
        }
        return false;
    }

    private static void writeDesktopMarker(File directory)
            throws IOException {

        File marker = new File(directory, DESKTOP_MARKER);
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(DESKTOP_MARKER_CONTENT);
            output.flush();
            try {
                output.getFD().sync();
            } catch (IOException ignored) {
                // Best effort only. Snapshot data has already been copied.
            }
        }
    }

    private static void copyDesktopSnapshotContents(
            File sourceDir,
            File targetDir,
            boolean syncFiles) throws IOException {

        File[] files = listFiles(sourceDir);
        if (!targetDir.exists()
                && !targetDir.mkdirs()
                && !targetDir.isDirectory()) {
            throw new IOException(
                    "Unable to create directory: "
                            + targetDir.getAbsolutePath());
        }

        for (File file : files) {
            if (DESKTOP_MARKER.equals(file.getName())) {
                continue;
            }
            copyRecursively(
                    file,
                    new File(targetDir, file.getName()),
                    syncFiles);
        }
    }

    private static void replaceSnapshot(
            File sourceDir,
            File targetDir,
            boolean syncFiles) throws IOException {

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

        copyRecursively(sourceDir, targetDir, syncFiles);
    }

    private static File androidExternalSaveDirectory(Object context)
            throws Exception {

        String packageName = (String) context.getClass()
                .getMethod("getPackageName")
                .invoke(context);
        return new File("/sdcard/Download/" + packageName);
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
                    "Android context is unavailable",
                    notAndroid);
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

    private static boolean hasAnyContent(File directory)
            throws IOException {

        return listFiles(directory).length > 0;
    }

    private static File[] listFiles(File directory)
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

    private static void deleteContents(File directory) throws IOException {
        if (directory == null
                || !directory.exists()
                || !directory.isDirectory()) {
            return;
        }

        File[] files = listFiles(directory);
        for (File file : files) {
            deleteRecursively(file);
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) {
            return;
        }

        if (file.isDirectory()) {
            File[] children = listFiles(file);
            for (File child : children) {
                deleteRecursively(child);
            }
        }

        if (!file.delete() && file.exists()) {
            throw new IOException(
                    "Unable to delete: " + file.getAbsolutePath());
        }

        System.out.println("SPD_Mod: [DEL OK] " + file.getAbsolutePath());
    }

    private static void copyRecursively(
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

            File[] files = listFiles(source);
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

        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {

            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }

            output.flush();
            if (syncFiles) {
                try {
                    output.getFD().sync();
                } catch (IOException ignored) {
                    // Existing import behavior treats fsync as best effort.
                }
            }
        }
    }
}
