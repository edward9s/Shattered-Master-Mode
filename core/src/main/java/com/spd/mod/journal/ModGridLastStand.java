package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.spd.mod.mechanics.ModLastStand;

/**
 * Journal entry for the permanent Last Stand Master Mode buff.
 */
public class ModGridLastStand extends ModGridEntry {

    public ModGridLastStand() {
        super(new BuffIcon(new ModLastStand(), true), title(), description());
    }

    private static String title() {
        return new ModLastStand().name();
    }

    private static String description() {
        return new ModLastStand().desc();
    }

    @Override
    public boolean onClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ModCharSelector.start(ModLastStand.class);
        return true;
    }
}
