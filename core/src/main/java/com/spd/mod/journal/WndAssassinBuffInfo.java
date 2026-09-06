package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.spd.mod.mechanics.ModAssassinBuff;

/** Assassin Instinct's live configuration window. */
public class WndAssassinBuffInfo extends WndInfoBuff {

    private static final int GAP = 3;
    private static final int BUTTON_HEIGHT = 18;

    public WndAssassinBuffInfo(final ModAssassinBuff buff) {
        super(buff);

        final RedButton instantKillButton = new RedButton(buttonText(buff), 8) {
            @Override
            protected void onClick() {
                if (buff.target == null || ModAssassinBuff.find(buff.target) != buff) {
                    WndAssassinBuffInfo.this.hide();
                    return;
                }

                buff.toggleInstantKill();
                ModTotalInfoOverlay.refreshIndicators();

                WndAssassinBuffInfo.this.hide();
                GameScene.show(new WndAssassinBuffInfo(buff));
            }
        };

        instantKillButton.setRect(0, height + GAP, width, BUTTON_HEIGHT);
        add(instantKillButton);
        resize(width, (int) instantKillButton.bottom() + 2);
    }

    private static String buttonText(ModAssassinBuff buff) {
        return buff.instantKillEnabled() ? "Instant Kill: ON" : "Instant Kill: OFF";
    }
}
