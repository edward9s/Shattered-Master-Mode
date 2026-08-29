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
                    + "Last Stand limits that damage to leave 1 HP, grants 3 turns of invulnerability and 30 turns of Bless, "
                    + "then restores HP to 50%. This protects against normal damage sources including falls and bleeding, "
                    + "but does not guarantee survival: hunger, direct death effects, and other mechanics that bypass normal shielding can still kill the bearer.";

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
