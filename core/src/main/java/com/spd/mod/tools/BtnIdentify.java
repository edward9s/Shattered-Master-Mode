package com.spd.mod.tools;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;

import com.spd.mod.mechanics.ModItemSelector;

public class BtnIdentify extends RedButton {

    public BtnIdentify() {
        super("Un/Identify");
    }

    @Override
    protected void onClick() {
        super.onClick();
        
        GameScene.selectItem(new ModItemSelector("com.spd.mod.mechanics.ModItemIdentify"));
    }
}
