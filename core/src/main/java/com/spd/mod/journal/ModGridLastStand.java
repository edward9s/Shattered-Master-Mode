package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.spd.mod.mechanics.ModLastStand;

/**
 * Journal entry for the permanent Last Stand Master Mode buff.
 */
public class ModGridLastStand extends ModGridEntry {

    private static final String TITLE = "Last Stand";
    private static final String DESCRIPTION =
            "Permanent Master Mode survival buff. While the bearer is still alive, Last Stand checks once per tick. "
                    + "If HP is below 10%, it restores HP to 50%, grants 3 turns of invulnerability, and grants 30 turns of Bless. "
                    + "It does not intercept lethal damage and does not guarantee survival; a sufficiently large hit or special death effect can kill before it activates.";

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
