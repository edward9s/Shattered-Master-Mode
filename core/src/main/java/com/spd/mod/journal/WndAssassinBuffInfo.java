package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.spd.mod.mechanics.ModAssassinBuff;
import com.watabou.noosa.ui.Component;

/** Assassin Instinct's live configuration window. */
public class WndAssassinBuffInfo extends WndInfoBuff {

    private static final int GAP = 3;
    private static final int BUTTON_HEIGHT = 18;

    public WndAssassinBuffInfo(final ModAssassinBuff buff) {
        super(buff);

        final RedButton accuracyButton = new RedButton(accuracyButtonText(buff), 8) {
            @Override
            protected void onClick() {
                if (!valid(buff)) {
                    WndAssassinBuffInfo.this.hide();
                    return;
                }

                buff.toggleInfiniteAccuracy();
                rebuild(buff);
            }
        };

        final Component controls = new Component() {
            @Override
            protected void layout() {
                accuracyButton.setRect(x, y, width, BUTTON_HEIGHT);
            }
        };
        controls.add(accuracyButton);
        controls.setSize(width, BUTTON_HEIGHT);

        if (!ModWindowCompat.addToBottom(this, controls, GAP, 2)) {
            controls.setPos(0, height + GAP);
            add(controls);
            resize(width, (int) controls.bottom() + 2);
        }
    }

    private boolean valid(ModAssassinBuff buff) {
        return buff.target != null && ModAssassinBuff.find(buff.target) == buff;
    }

    private void rebuild(ModAssassinBuff buff) {
        ModTotalInfoOverlay.refreshIndicators();
        hide();
        GameScene.show(new WndAssassinBuffInfo(buff));
    }

    private static String accuracyButtonText(ModAssassinBuff buff) {
        return buff.infiniteAccuracyEnabled() ? "Infinite Accuracy: ON" : "Infinite Accuracy: OFF";
    }
}
