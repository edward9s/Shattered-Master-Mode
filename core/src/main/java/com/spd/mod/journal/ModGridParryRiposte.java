package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.spd.mod.mechanics.ModParryRiposte;

/**
 * Journal entry for the single Total buff. Clicking the entry only applies or
 * removes Total, exactly like other journal buffs. Riposte is configured from
 * the live buff's information window, not from the journal grid.
 */
public class ModGridParryRiposte extends ModGridEntry {

    private static final String TITLE = "Total";
    private static final String DESCRIPTION =
            "Permanent Master Mode combat buff. Total always parries incoming attacks handled by the normal hit check. "
                    + "Open the applied buff's information window to turn riposte on or off. When riposte is enabled, "
                    + "every parried attack immediately triggers a guaranteed counterattack.";

    public ModGridParryRiposte() {
        super(new BuffIcon(new ModParryRiposte(), true), TITLE, DESCRIPTION);
    }

    @Override
    public boolean onClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ModCharSelector.start(ModParryRiposte.class);
        return true;
    }
}
