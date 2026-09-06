package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.spd.mod.mechanics.ModInstantKillBuff;

/** Journal entry for the permanent Instant Kill Master Mode buff. */
public class ModGridInstantKillBuff extends ModGridEntry {

    private static final String TITLE = "Instant Kill";
    private static final String DESCRIPTION =
            "Permanent configurable combat buff for the Hero. Attach it here, then tap its live buff icon to toggle Instant Kill and Infinite Accuracy independently. Both settings default to OFF and persist with the buff.";

    public ModGridInstantKillBuff() {
        super(new BuffIcon(new ModInstantKillBuff(), true), TITLE, DESCRIPTION);
    }

    @Override
    public boolean onClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ModCharSelector.startHeroOnly(ModInstantKillBuff.class);
        return true;
    }
}
