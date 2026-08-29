package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.spd.mod.mechanics.ModEnemySurge;

/** Journal entry for the permanent Enemy Surge Master Mode buff. */
public class ModGridEnemySurge extends ModGridEntry {

    private static final String TITLE = "Enemy Surge";
    private static final String DESCRIPTION =
            "Permanent Master Mode buff. Its multiplier is configured from the applied buff's information window by copying the current Tools Level / Quantity slider value (1-10); 1x keeps both normal respawn speed and the normal enemy population limit. "
                    + "Higher values multiply both natural respawn speed and the allowed enemy population. The same buff information window also toggles whether enemies are periodically attracted toward the buff bearer. "
                    + "Only one Enemy Surge can be active at a time; applying it to another character transfers it.";

    public ModGridEnemySurge() {
        super(new BuffIcon(new ModEnemySurge(), true), TITLE, DESCRIPTION);
    }

    @Override
    public boolean onClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ModCharSelector.start(ModEnemySurge.class);
        return true;
    }
}
