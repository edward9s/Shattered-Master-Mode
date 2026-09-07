package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.spd.mod.mechanics.ModParryRiposte;

/** Total's live buff information window with independent Parry/Riposte controls. */
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
        parryButton.setRect(0, height + GAP, width, BUTTON_HEIGHT);
        add(parryButton);

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
        riposteButton.setRect(0, parryButton.bottom() + GAP, width, BUTTON_HEIGHT);
        add(riposteButton);

        resize(width, (int) riposteButton.bottom() + 2);
    }

    private boolean isCurrent(ModParryRiposte buff) {
        return buff.target != null && ModParryRiposte.find(buff.target) == buff;
    }

    private void rebuild(ModParryRiposte buff) {
        WndTotalBuffInfo.this.hide();
        GameScene.show(new WndTotalBuffInfo(buff));
    }

    private static String parryButtonText(ModParryRiposte buff) {
        return buff.parryEnabled() ? "Parry: ON" : "Parry: OFF";
    }

    private static String riposteButtonText(ModParryRiposte buff) {
        return buff.riposteEnabled() ? "Riposte: ON" : "Riposte: OFF";
    }
}
