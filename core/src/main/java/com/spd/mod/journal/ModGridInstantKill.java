package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.spd.mod.mechanics.ModInstantKill;

/** Journal entry for the permanent Instant Kill Master Mode buff. */
public class ModGridInstantKill extends ModGridEntry {

    public ModGridInstantKill() {
        super(new BuffIcon(new ModInstantKill(), true), title(), description());
    }

    private static String title() {
        return new ModInstantKill().name();
    }

    private static String description() {
        return new ModInstantKill().desc();
    }

    @Override
    public boolean onClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ModCharSelector.startHeroOnly(ModInstantKill.class);
        return true;
    }
}
