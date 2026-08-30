package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.spd.mod.mechanics.ModAssassinInstinct;

/** Journal entry for the permanent Assassin Instinct Master Mode buff. */
public class ModGridAssassinInstinct extends ModGridEntry {

    private static final String TITLE = "Assassin Instinct";
    private static final String DESCRIPTION =
            "Permanent Master Mode buff for the Hero. Press and hold a normal map cell or character for about half a second to invoke the existing Mod Assassin action on that cell. "
                    + "Dragging, pinching, ordinary taps, and other targeting modes keep their original controls.";

    public ModGridAssassinInstinct() {
        super(new BuffIcon(new ModAssassinInstinct(), true), TITLE, DESCRIPTION);
    }

    @Override
    public boolean onClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ModCharSelector.startHeroOnly(ModAssassinInstinct.class);
        return true;
    }
}
