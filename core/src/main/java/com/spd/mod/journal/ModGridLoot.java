package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.spd.mod.mechanics.ModLootBuff;

/** Journal entry for the permanent Loot Master Mode buff. */
public class ModGridLoot extends ModGridEntry {

    private static final String TITLE = "Loot";
    private static final String DESCRIPTION =
            "Permanent Hero-only Master Mode buff using the same shared Loot storage mechanics as Scroll of Loot. "
                    + "Tap the applied buff icon to open Loot / Put / Take / Dump and directly use stored items. "
                    + "Removing the buff returns stored items to the Hero or drops them at the Hero's feet if the bags are full.";

    public ModGridLoot() {
        super(new BuffIcon(new ModLootBuff(), true), TITLE, DESCRIPTION);
    }

    @Override
    public boolean onClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ModCharSelector.startHeroOnly(ModLootBuff.class);
        return true;
    }
}
