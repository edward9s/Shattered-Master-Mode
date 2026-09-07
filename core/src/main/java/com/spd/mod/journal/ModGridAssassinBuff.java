package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.spd.mod.mechanics.ModAssassinBuff;

/** Journal entry for the permanent Assassin Instinct Master Mode buff. */
public class ModGridAssassinBuff extends ModGridEntry {

    public ModGridAssassinBuff() {
        ModAssassinBuff buff = new ModAssassinBuff();
        super(new BuffIcon(buff, true), buff.name(), buff.desc());
    }

    @Override
    public boolean onClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ModCharSelector.startHeroOnly(ModAssassinBuff.class);
        return true;
    }
}
