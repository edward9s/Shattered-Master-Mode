package com.spd.mod.tools;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;

import com.spd.mod.mechanics.ModItemLevel;
import com.spd.mod.mechanics.ModItemSelector;

public class BtnUpgrade extends RedButton {

    public BtnUpgrade() {
        super("Upgrade");
    }

    @Override
    protected void onClick() {
        super.onClick();
        
        ModItemLevel.opMode = 1;
        GameScene.selectItem(new ModItemSelector("com.spd.mod.mechanics.ModItemLevel"));
    }
}
