package com.spd.mod.tools;

import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.spd.mod.mechanics.ModSaveTransfer;

public class BtnImportSave extends RedButton {

    public BtnImportSave() {
        super("Import Save");
        textColor(0xffff44);
    }

    @Override
    protected void onClick() {
        super.onClick();

        try {
            ModSaveTransfer.importSave();
        } catch (Exception e) {
            System.out.println("SPD_Mod: Import Crash - " + e.getMessage());
            GLog.w("Import failed!", new Object[0]);
        }
    }
}
