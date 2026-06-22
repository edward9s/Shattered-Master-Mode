package com.spd.mod.tools;

import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;

import com.spd.mod.ModGame;

public class BtnImportSave extends RedButton {

    public BtnImportSave() {
        super("Import Save");
        textColor(0xffff44);
    }

    @Override
    protected void onClick() {
        super.onClick();

        System.out.println("SPD_Mod: === IMPORT START ===");

        try {
            Class<?> buildVersionClass = Class.forName("android.os.Build$VERSION");
            int sdkInt = buildVersionClass.getField("SDK_INT").getInt(null);

            if (sdkInt >= 30) {
                Class<?> envClass = Class.forName("android.os.Environment");
                boolean isManager = (boolean) envClass.getMethod("isExternalStorageManager").invoke(null);

                if (!isManager) {
                    Object context = ModGame.getSystemContext();
                    Class<?> intentClass = Class.forName("android.content.Intent");
                    Object intent = intentClass.getConstructor(String.class).newInstance("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");

                    Class<?> uriClass = Class.forName("android.net.Uri");
                    Method getPackageName = context.getClass().getMethod("getPackageName");
                    String pkg = (String) getPackageName.invoke(context);
                    Object uri = uriClass.getMethod("fromParts", String.class, String.class, String.class).invoke(null, "package", pkg, null);

                    intentClass.getMethod("setData", uriClass).invoke(intent, uri);
                    intentClass.getMethod("addFlags", int.class).invoke(intent, 0x10000000);

                    context.getClass().getMethod("startActivity", intentClass).invoke(context, intent);
                    return;
                }
            }

            Object context = ModGame.getSystemContext();
            if (context == null) {
                System.out.println("SPD_Mod: Context is null!");
                return;
            }

            Method getPackageNameMethod = context.getClass().getMethod("getPackageName");
            String packageName = (String) getPackageNameMethod.invoke(context);
            File sourceDir = new File("/sdcard/Download/" + packageName);

            Method getFilesDirMethod = context.getClass().getMethod("getFilesDir");
            File targetDir = (File) getFilesDirMethod.invoke(context);

            // 安全保護：只有當外部來源存在且確實有檔案時，才清空本地存檔。
            // 避免「來源是空的 / 沒有權限」時，先把本地存檔刪光卻沒東西可還原。
            File[] sourceFiles = sourceDir.listFiles();
            if (!sourceDir.exists() || !sourceDir.isDirectory() || sourceFiles == null || sourceFiles.length == 0) {
                System.out.println("SPD_Mod: Import aborted - no valid save at " + sourceDir.getAbsolutePath());
                GLog.w("No save to import!", new Object[0]);
                return;
            }

            // 需求 1：匯入前先把遊戲存檔資料夾底下的檔案都刪除。
            // 刪除資料夾「內容」，但保留資料夾本身（App 預期此目錄存在）。
            System.out.println("SPD_Mod: Clearing local save folder: " + targetDir.getAbsolutePath());
            deleteContents(targetDir);

            recursiveCopyLog(sourceDir, targetDir);

            System.out.println("SPD_Mod: === IMPORT DONE, KILLING ===");
            Class<?> processClass = Class.forName("android.os.Process");
            int pid = (int) processClass.getMethod("myPid").invoke(null);
            processClass.getMethod("killProcess", int.class).invoke(null, pid);

        } catch (Exception e) {
            System.out.println("SPD_Mod: Import Crash - " + e.getMessage());
            GLog.w("Import failed!", new Object[0]);
        }
    }

    // 刪除目錄底下所有內容（檔案與子目錄），但不刪除 dir 本身。
    private void deleteContents(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            deleteRecursively(file);
        }
    }

    // 遞迴刪除單一檔案或整個子樹（先刪子項，最後刪自己，確保目錄為空才刪得掉）。
    private void deleteRecursively(File file) {
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
        System.out.println("SPD_Mod: " + (deleted ? "[DEL OK] " : "[DEL FAIL] ") + file.getAbsolutePath());
    }

    private void recursiveCopyLog(File src, File dst) throws IOException {
        File[] files = src.listFiles();

        if (files == null) {
            System.out.println("SPD_Mod: FAIL: listFiles() returned NULL for " + src.getName());
            return;
        }

        if (files.length == 0) {
            return;
        }

        for (File file : files) {
            boolean isDir = file.isDirectory();
            System.out.println("SPD_Mod: " + (isDir ? "[DIR]  " : "[FILE] ") + file.getName());

            File target = new File(dst, file.getName());

            if (isDir) {
                if (target.exists() && target.isFile()) {
                    target.delete();
                }
                target.mkdirs();
                recursiveCopyLog(file, target);
            } else {
                if (target.exists()) {
                    target.delete();
                }
                copySingleFile(file, target);
            }
        }
    }

    private void copySingleFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);

        byte[] buffer = new byte[1024];
        int length;

        while ((length = in.read(buffer)) != -1) {
            out.write(buffer, 0, length);
        }

        out.flush();
        try {
            out.getFD().sync();
        } catch (Exception e) {
        }

        in.close();
        out.close();
    }
}
