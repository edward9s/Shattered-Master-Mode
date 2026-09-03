package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.spd.mod.mechanics.ModAssassinBuff;

/** Journal entry for the permanent Assassin Instinct Master Mode buff. */
public class ModGridAssassinBuff extends ModGridEntry {

    private static final String TITLE = "Assassin Instinct";
    private static final String DESCRIPTION =
            "Permanent Master Mode buff for the Hero. Press and hold a normal map cell or character for about half a second to invoke the existing Mod Assassin action on that cell. "
                    + "Dragging, pinching, ordinary taps, and other targeting modes keep their original controls.";

    public ModGridAssassinBuff() {
        super(new BuffIcon(new ModAssassinBuff(), true), TITLE, DESCRIPTION);
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
