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
