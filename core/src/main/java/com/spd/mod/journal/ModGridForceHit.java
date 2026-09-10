package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.spd.mod.mechanics.ModForceHit;

/** Journal entry for the permanent Force Hit Master Mode buff. */
public class ModGridForceHit extends ModGridEntry {

    public ModGridForceHit() {
        super(new BuffIcon(new ModForceHit(), true), title(), description());
    }

    private static String title() {
        return new ModForceHit().name();
    }

    private static String description() {
        return new ModForceHit().desc();
    }

    @Override
    public boolean onClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ModCharSelector.startHeroOnly(ModForceHit.class);
        return true;
    }
}
