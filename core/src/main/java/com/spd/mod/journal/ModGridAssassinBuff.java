package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.spd.mod.mechanics.ModAssassinate;

/** Journal entry for the permanent Assassin Instinct Master Mode buff. */
public class ModGridAssassinBuff extends ModGridEntry {

    public ModGridAssassinBuff() {
        super(new BuffIcon(new ModAssassinate(), true), title(), description());
    }

    private static String title() {
        return new ModAssassinate().name();
    }

    private static String description() {
        return new ModAssassinate().desc();
    }

    @Override
    public boolean onClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ModCharSelector.startHeroOnly(ModAssassinate.class);
        return true;
    }
}
