package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.spd.mod.mechanics.ModParryRiposte;

/**
 * Journal entry for Total Parry / Riposte. Clicking the entry only applies or
 * removes the buff, exactly like other journal buffs. Riposte is configured
 * from the live buff's information window, not from the journal grid.
 */
public class ModGridParryRiposte extends ModGridEntry {

    public ModGridParryRiposte() {
        super(new BuffIcon(new ModParryRiposte(), true), title(), description());
    }

    private static String title() {
        return new ModParryRiposte().name();
    }

    private static String description() {
        return new ModParryRiposte().desc();
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
