package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.spd.mod.mechanics.ModParryRiposte;

public class ModGridParryRiposte extends ModGridEntry {

    private static final String TITLE = "Mod Parry / Riposte";
    private static final String DESCRIPTION =
            "Permanent Master Mode combat buff. Parry makes every attack roll against the target miss. "
                    + "Riposte leaves incoming attacks unchanged and immediately makes a guaranteed normal "
                    + "counterattack after each successful standard attack when the target can attack the attacker. "
                    + "Choose the same active mode again to remove the buff.";

    public ModGridParryRiposte() {
        super(new BuffIcon(new ModParryRiposte(), true), TITLE, DESCRIPTION);
    }

    @Override
    public boolean onClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ShatteredPixelDungeon.scene().addToFront(new WndOptions(
                new BuffIcon(new ModParryRiposte(), true),
                TITLE,
                "Choose a permanent mode, then select a character. Selecting the same active mode again removes the buff.",
                "Parry (all)",
                "Riposte (all)") {
            @Override
            protected void onSelect(int index) {
                ModParryRiposteSelector.start(
                        index == 0 ? ModParryRiposte.Mode.PARRY : ModParryRiposte.Mode.RIPOSTE);
            }
        });
        return true;
    }
}
