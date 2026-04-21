package com.spd.mod.tools;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;

import com.spd.mod.mechanics.ModItemSelector;

public class BtnCurse extends RedButton {

    public BtnCurse() {
        super("Un/Curse");
    }

    @Override
    protected void onClick() {
        super.onClick();
        ModItemSelector selector = new ModItemSelector("com.spd.mod.mechanics.ModItemCurse");
        GameScene.selectItem(selector);
    }
}
