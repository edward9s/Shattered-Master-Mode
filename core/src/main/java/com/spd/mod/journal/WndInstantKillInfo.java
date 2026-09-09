package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.spd.mod.mechanics.ModInstantKill;
import com.watabou.noosa.ui.Component;

/** Instant Kill's live configuration window. */
public class WndInstantKillInfo extends WndInfoBuff {

    private static final int GAP = 3;
    private static final int BUTTON_HEIGHT = 18;

    public WndInstantKillInfo(final ModInstantKill buff) {
        super(buff);

        final RedButton instantKillButton = new RedButton(instantKillButtonText(buff), 8) {
            @Override
            protected void onClick() {
                if (!valid(buff)) {
                    WndInstantKillInfo.this.hide();
                    return;
                }

                buff.toggleInstantKill();
                rebuild(buff);
            }
        };

        final RedButton accuracyButton = new RedButton(accuracyButtonText(buff), 8) {
            @Override
            protected void onClick() {
                if (!valid(buff)) {
                    WndInstantKillInfo.this.hide();
                    return;
                }

                buff.toggleInfiniteAccuracy();
                rebuild(buff);
            }
        };

        final Component controls = new Component() {
            @Override
            protected void layout() {
                instantKillButton.setRect(x, y, width, BUTTON_HEIGHT);
                accuracyButton.setRect(x, instantKillButton.bottom() + GAP, width, BUTTON_HEIGHT);
            }
        };
        controls.add(instantKillButton);
        controls.add(accuracyButton);
        controls.setSize(width, BUTTON_HEIGHT * 2 + GAP);

        if (!ModWindowCompat.addToBottom(this, controls, GAP, 2)) {
            controls.setPos(0, height + GAP);
            add(controls);
            resize(width, (int) controls.bottom() + 2);
        }
    }

    private boolean valid(ModInstantKill buff) {
        return buff.target != null && ModInstantKill.find(buff.target) == buff;
    }

    private void rebuild(ModInstantKill buff) {
        ModTotalInfoOverlay.refreshIndicators();
        hide();
        GameScene.show(new WndInstantKillInfo(buff));
    }

    private static String instantKillButtonText(ModInstantKill buff) {
        return buff.instantKillEnabled() ? "Instant Kill: ON" : "Instant Kill: OFF";
    }

    private static String accuracyButtonText(ModInstantKill buff) {
        return buff.infiniteAccuracyEnabled() ? "Infinite Accuracy: ON" : "Infinite Accuracy: OFF";
    }
}
