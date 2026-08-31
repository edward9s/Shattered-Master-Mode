package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.spd.mod.mechanics.ModLastStand;

/**
 * Journal entry for the permanent Last Stand Master Mode buff.
 */
public class ModGridLastStand extends ModGridEntry {

    private static final String TITLE = "Last Stand";
    private static final String DESCRIPTION =
            "Permanent Master Mode survival buff. If damage handled by the normal shielding system would be lethal, "
                    + "Last Stand limits that damage to leave 1 HP. Whenever the bearer is alive at exactly 1 HP when Last Stand acts, "
                    + "it restores HP to 25% and cures the same status ailments as a blessed Ankh, but grants no invulnerability and does not reset hunger. "
                    + "It does not guarantee survival: damage that bypasses normal shielding can still kill if it skips past 1 HP, and direct death effects can also bypass Last Stand.";

    public ModGridLastStand() {
        super(new BuffIcon(new ModLastStand(), true), TITLE, DESCRIPTION);
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
