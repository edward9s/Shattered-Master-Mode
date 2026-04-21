package com.spd.mod.tools;

import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;

import com.spd.mod.mechanics.ModItemAlchemize;

public class BtnAlchemize extends RedButton {

    public BtnAlchemize() {
        super("Alchemize");
    }

    @Override
    protected void onClick() {
        super.onClick();
        ModItemAlchemize.openSelector();
    }
}
