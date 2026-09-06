package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.spd.mod.mechanics.ModParryRiposte;

/**
 * Total's live buff information window. Configuration switches belong here so
 * inspecting/configuring Total never requires choosing a second journal buff.
 */
public class WndTotalBuffInfo extends WndInfoBuff {

    private static final int GAP = 3;
    private static final int BUTTON_HEIGHT = 18;

    public WndTotalBuffInfo(final ModParryRiposte buff) {
        super(buff);

        final RedButton riposteButton = new RedButton(riposteButtonText(buff), 8) {
            @Override
            protected void onClick() {
                if (!valid(buff)) {
                    WndTotalBuffInfo.this.hide();
                    return;
                }

                buff.toggleRiposte();
                rebuild(buff);
            }
        };

        riposteButton.setRect(0, height + GAP, width, BUTTON_HEIGHT);
        add(riposteButton);

        final RedButton instantKillButton = new RedButton(instantKillButtonText(buff), 8) {
            @Override
            protected void onClick() {
                if (!valid(buff)) {
                    WndTotalBuffInfo.this.hide();
                    return;
                }

                buff.toggleInstantKill();
                rebuild(buff);
            }
        };

        instantKillButton.setRect(0, riposteButton.bottom() + GAP, width, BUTTON_HEIGHT);
        add(instantKillButton);
        resize(width, (int) instantKillButton.bottom() + 2);
    }

    private boolean valid(ModParryRiposte buff) {
        return buff.target != null && ModParryRiposte.find(buff.target) == buff;
    }

    private void rebuild(ModParryRiposte buff) {
        ModTotalInfoOverlay.refreshIndicators();
        hide();
        GameScene.show(new WndTotalBuffInfo(buff));
    }

    private static String riposteButtonText(ModParryRiposte buff) {
        return buff.riposteEnabled() ? "Riposte: ON" : "Riposte: OFF";
    }

    private static String instantKillButtonText(ModParryRiposte buff) {
        return buff.instantKillEnabled() ? "Instant Kill: ON" : "Instant Kill: OFF";
    }
}
