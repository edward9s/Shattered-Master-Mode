package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.spd.mod.mechanics.ModInstantKillBuff;

/** Instant Kill's live configuration window. */
public class WndInstantKillBuffInfo extends WndInfoBuff {

    private static final int GAP = 3;
    private static final int BUTTON_HEIGHT = 18;

    public WndInstantKillBuffInfo(final ModInstantKillBuff buff) {
        super(buff);

        final RedButton instantKillButton = new RedButton(instantKillButtonText(buff), 8) {
            @Override
            protected void onClick() {
                if (!valid(buff)) {
                    WndInstantKillBuffInfo.this.hide();
                    return;
                }

                buff.toggleInstantKill();
                rebuild(buff);
            }
        };
        instantKillButton.setRect(0, height + GAP, width, BUTTON_HEIGHT);
        add(instantKillButton);

        final RedButton accuracyButton = new RedButton(accuracyButtonText(buff), 8) {
            @Override
            protected void onClick() {
                if (!valid(buff)) {
                    WndInstantKillBuffInfo.this.hide();
                    return;
                }

                buff.toggleInfiniteAccuracy();
                rebuild(buff);
            }
        };
        accuracyButton.setRect(0, instantKillButton.bottom() + GAP, width, BUTTON_HEIGHT);
        add(accuracyButton);
        resize(width, (int) accuracyButton.bottom() + 2);
    }

    private boolean valid(ModInstantKillBuff buff) {
        return buff.target != null && ModInstantKillBuff.find(buff.target) == buff;
    }

    private void rebuild(ModInstantKillBuff buff) {
        ModTotalInfoOverlay.refreshIndicators();
        hide();
        GameScene.show(new WndInstantKillBuffInfo(buff));
    }

    private static String instantKillButtonText(ModInstantKillBuff buff) {
        return buff.instantKillEnabled() ? "Instant Kill: ON" : "Instant Kill: OFF";
    }

    private static String accuracyButtonText(ModInstantKillBuff buff) {
        return buff.infiniteAccuracyEnabled() ? "Infinite Accuracy: ON" : "Infinite Accuracy: OFF";
    }
}
