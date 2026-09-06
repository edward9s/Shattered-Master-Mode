package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.spd.mod.mechanics.ModInstantKillBuff;

/** Journal entry for the permanent Instant Kill Master Mode buff. */
public class ModGridInstantKillBuff extends ModGridEntry {

    private static final String TITLE = "Instant Kill";
    private static final String DESCRIPTION =
            "Permanent Master Mode buff for the Hero. Successful normal attacks, including melee and thrown weapons, invoke the enemy's native death behavior.";

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
