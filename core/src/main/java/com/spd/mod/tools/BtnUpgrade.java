package com.spd.mod.tools;

import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;

import com.spd.mod.mechanics.ModItemLevel;

public class BtnUpgrade extends RedButton {

    public BtnUpgrade() {
        super("Upgrade");
    }

    @Override
    protected void onClick() {
        super.onClick();
        
        ModItemLevel.opMode = 1;
        ModItemLevel.openSelector();
    }
}
