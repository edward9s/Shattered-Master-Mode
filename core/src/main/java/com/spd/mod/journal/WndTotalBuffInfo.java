package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.spd.mod.mechanics.ModParryRiposte;

/**
 * Total's live buff information window. The riposte switch belongs here so
 * inspecting/configuring Total never requires choosing a second journal buff.
 */
public class WndTotalBuffInfo extends WndInfoBuff {

    private static final int GAP = 3;
    private static final int BUTTON_HEIGHT = 18;

    public WndTotalBuffInfo(final ModParryRiposte buff) {
        super(buff);

        final RedButton riposteButton = new RedButton(buttonText(buff), 8) {
            @Override
            protected void onClick() {
                if (buff.target == null || ModParryRiposte.find(buff.target) != buff) {
                    WndTotalBuffInfo.this.hide();
                    return;
                }

                buff.toggleRiposte();
                ModTotalInfoOverlay.refreshIndicators();

                // Rebuild the same info window so its description, icon and
                // button state immediately reflect the new setting.
                WndTotalBuffInfo.this.hide();
                GameScene.show(new WndTotalBuffInfo(buff));
            }
        };

        riposteButton.setRect(0, height + GAP, width, BUTTON_HEIGHT);
        add(riposteButton);
        resize(width, (int) riposteButton.bottom() + 2);
    }

    private static String buttonText(ModParryRiposte buff) {
        return buff.riposteEnabled() ? "Riposte: ON" : "Riposte: OFF";
    }
}
