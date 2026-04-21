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
        Dungeon.saveAll();

        try {
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

            if (!targetDir.exists()) {
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
