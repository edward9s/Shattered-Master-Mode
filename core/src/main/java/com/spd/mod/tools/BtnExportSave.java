package com.spd.mod.tools;

import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.spd.mod.mechanics.ModSaveTransfer;

public class BtnExportSave extends RedButton {

    public BtnExportSave() {
        super("Export Save");
        textColor(0xffff44);
    }

    @Override
    protected void onClick() {
        super.onClick();

        try {
            ModSaveTransfer.exportSave();
        } catch (Exception e) {
            System.out.println("SPD_Mod: Export Crash - " + e.getMessage());
            GLog.w("Export failed!", new Object[0]);
        }
    }
}
