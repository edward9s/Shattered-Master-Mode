package com.spd.mod.tools;

import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;

import com.spd.mod.mechanics.ModItemIdentify;

public class BtnIdentify extends RedButton {

    public BtnIdentify() {
        super("Un/Identify");
    }

    @Override
    protected void onClick() {
        super.onClick();
        
        ModItemIdentify.openSelector();
    }
}
