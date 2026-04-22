package com.spd.mod.tools;

import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;

import com.spd.mod.mechanics.ModItemCurse;

public class BtnCurse extends RedButton {

    public BtnCurse() {
        super("Un/Curse");
    }

    @Override
    protected void onClick() {
        super.onClick();
        ModItemCurse.openSelector();
    }
}
