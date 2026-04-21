package com.spd.mod.tools;

import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;

import com.spd.mod.mechanics.ModItemLevel;
import com.spd.mod.mechanics.ModItemSelector;

public class BtnDegrade extends RedButton {

    public BtnDegrade() {
        super("Downgrade");
    }

    @Override
    protected void onClick() {
        super.onClick();
        
        ModItemLevel.opMode = 2;
        GameScene.selectItem(new ModItemSelector("com.spd.mod.mechanics.ModItemLevel"));
    }
}
