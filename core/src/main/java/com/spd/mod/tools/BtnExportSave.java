package com.spd.mod.tools;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

import com.spd.mod.ModGame;

public class BtnExportSave extends RedButton {

    public BtnExportSave() {
        super("Export Save");
        textColor(0xffff44);
    }

    @Override
    protected void onClick() {
        super.onClick();

        System.out.println("SPD_Mod: === EXPORT START ===");

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

            Dungeon.saveAll();
            Object context = ModGame.getSystemContext();

            if (context == null) {
                System.out.println("SPD_Mod: Context is null! Export failed.");
                return;
            }

            Method getFilesDirMethod = context.getClass().getMethod("getFilesDir");
            File sourceDir = (File) getFilesDirMethod.invoke(context);
            System.out.println("SPD_Mod: Source: " + sourceDir.getAbsolutePath());

            Method getPackageNameMethod = context.getClass().getMethod("getPackageName");
            String packageName = (String) getPackageNameMethod.invoke(context);
            String targetPath = "/sdcard/Download/" + packageName;
            File targetDir = new File(targetPath);

            // 需求 2：匯出前先把外部資料夾內的存檔都刪除（如果有的話）。
            // 若資料夾已存在則清空其內容（保留資料夾）；否則建立資料夾。
            if (targetDir.exists()) {
                System.out.println("SPD_Mod: Clearing external folder: " + targetDir.getAbsolutePath());
                deleteContents(targetDir);
            } else {
                targetDir.mkdirs();
            }

            copyRecursively(sourceDir, targetDir);

            System.out.println("SPD_Mod: === EXPORT FINISHED ===");
            GLog.h("Save exported!", new Object[0]);

        } catch (Exception e) {
            System.out.println("SPD_Mod: Export Crash - " + e.getMessage());
            GLog.w("Export failed!", new Object[0]);
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

    private void copyRecursively(File src, File dst) throws IOException {
        if (!src.exists()) {
            return;
        }

        if (src.isDirectory()) {
            if (!dst.exists()) {
                dst.mkdirs();
            }

            String[] files = src.list();
            if (files == null) {
                return;
            }

            for (String file : files) {
                File srcFile = new File(src, file);
                File dstFile = new File(dst, file);
                copyRecursively(srcFile, dstFile);
            }
        } else {
            System.out.println("SPD_Mod: Exp: " + src.getName() + " > " + dst.getAbsolutePath());

            InputStream in = new FileInputStream(src);
            OutputStream out = new FileOutputStream(dst);
            byte[] buffer = new byte[1024];
            int length;

            while ((length = in.read(buffer)) != -1) {
                out.write(buffer, 0, length);
            }

            out.flush();
            in.close();
            out.close();
        }
    }
}
