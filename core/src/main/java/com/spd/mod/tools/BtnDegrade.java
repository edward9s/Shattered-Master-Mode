package com.spd.mod.tools;

import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;

import com.spd.mod.mechanics.ModItemLevel;

public class BtnDegrade extends RedButton {

    public BtnDegrade() {
        super("Downgrade");
    }

    @Override
    protected void onClick() {
        super.onClick();
        
        ModItemLevel.opMode = 2;
        ModItemLevel.openSelector();
    }
}
