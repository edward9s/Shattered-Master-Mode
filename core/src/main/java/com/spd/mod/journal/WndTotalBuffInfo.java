package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.spd.mod.mechanics.ModAssassinBuff;
import com.spd.mod.mechanics.ModParryRiposte;
import com.watabou.noosa.ui.Component;

/** Shared live configuration window for configurable Master Mode combat buffs. */
public class WndTotalBuffInfo extends WndInfoBuff {

    private static final int GAP = 3;
    private static final int BUTTON_HEIGHT = 18;

    public WndTotalBuffInfo(final ModParryRiposte buff) {
        super(buff);

        final RedButton parryButton = new RedButton(parryButtonText(buff), 8) {
            @Override
            protected void onClick() {
                if (!isCurrent(buff)) {
                    WndTotalBuffInfo.this.hide();
                    return;
                }

                buff.toggleParry();
                ModTotalInfoOverlay.refreshIndicators();
                rebuild(buff);
            }
        };

        final RedButton riposteButton = new RedButton(riposteButtonText(buff), 8) {
            @Override
            protected void onClick() {
                if (!isCurrent(buff)) {
                    WndTotalBuffInfo.this.hide();
                    return;
                }

                buff.toggleRiposte();
                ModTotalInfoOverlay.refreshIndicators();
                rebuild(buff);
            }
        };

        final Component controls = new Component() {
            @Override
            protected void layout() {
                parryButton.setRect(x, y, width, BUTTON_HEIGHT);
                riposteButton.setRect(x, parryButton.bottom() + GAP, width, BUTTON_HEIGHT);
            }
        };
        controls.add(parryButton);
        controls.add(riposteButton);
        controls.setSize(width, BUTTON_HEIGHT * 2 + GAP);

        addControls(controls);
    }

    public WndTotalBuffInfo(final ModAssassinBuff buff) {
        super(buff);

        final RedButton accuracyButton = new RedButton(accuracyButtonText(buff), 8) {
            @Override
            protected void onClick() {
                if (!isCurrent(buff)) {
                    WndTotalBuffInfo.this.hide();
                    return;
                }

                buff.toggleInfiniteAccuracy();
                ModTotalInfoOverlay.refreshIndicators();
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

        addControls(controls);
    }

    private void addControls(Component controls) {
        if (!ModWindowCompat.addToBottom(this, controls, GAP, 2)) {
            controls.setPos(0, height + GAP);
            add(controls);
            resize(width, (int) controls.bottom() + 2);
        }
    }

    private boolean isCurrent(ModParryRiposte buff) {
        return buff.target != null && ModParryRiposte.find(buff.target) == buff;
    }

    private boolean isCurrent(ModAssassinBuff buff) {
        return buff.target != null && ModAssassinBuff.find(buff.target) == buff;
    }

    private void rebuild(ModParryRiposte buff) {
        WndTotalBuffInfo.this.hide();
        GameScene.show(new WndTotalBuffInfo(buff));
    }

    private void rebuild(ModAssassinBuff buff) {
        WndTotalBuffInfo.this.hide();
        GameScene.show(new WndTotalBuffInfo(buff));
    }

    private static String parryButtonText(ModParryRiposte buff) {
        return buff.parryEnabled() ? "Parry: ON" : "Parry: OFF";
    }

    private static String riposteButtonText(ModParryRiposte buff) {
        return buff.riposteEnabled() ? "Riposte: ON" : "Riposte: OFF";
    }

    private static String accuracyButtonText(ModAssassinBuff buff) {
        return buff.infiniteAccuracyEnabled() ? "Basic Accuracy: ON" : "Basic Accuracy: OFF";
    }
}
