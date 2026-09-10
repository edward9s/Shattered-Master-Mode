package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.spd.mod.mechanics.ModParryRiposte;
import com.watabou.noosa.ui.Component;

/** Live configuration window for Total Parry / Riposte. */
public class WndTotalBuffInfo extends WndInfoBuff {

    private static final int GAP = 3;
    private static final int CHECK_HEIGHT = 18;

    public WndTotalBuffInfo(final ModParryRiposte buff) {
        super(buff);

        final CheckBox parryCheck = new CheckBox("Parry") {
            @Override
            protected void onClick() {
                if (!isCurrent(buff)) {
                    WndTotalBuffInfo.this.hide();
                    return;
                }

                super.onClick();
                buff.toggleParry();
                checked(buff.parryEnabled());
                ModTotalInfoOverlay.refreshIndicators();
            }
        };
        parryCheck.checked(buff.parryEnabled());

        final CheckBox riposteCheck = new CheckBox("Riposte") {
            @Override
            protected void onClick() {
                if (!isCurrent(buff)) {
                    WndTotalBuffInfo.this.hide();
                    return;
                }

                super.onClick();
                buff.toggleRiposte();
                checked(buff.riposteEnabled());
                ModTotalInfoOverlay.refreshIndicators();
            }
        };
        riposteCheck.checked(buff.riposteEnabled());

        final Component controls = new Component() {
            @Override
            protected void layout() {
                parryCheck.setRect(x, y, width, CHECK_HEIGHT);
                riposteCheck.setRect(x, parryCheck.bottom() + GAP, width, CHECK_HEIGHT);
            }
        };
        controls.add(parryCheck);
        controls.add(riposteCheck);
        controls.setSize(width, CHECK_HEIGHT * 2 + GAP);

        if (!ModWindowCompat.addToBottom(this, controls, GAP, 2)) {
            controls.setPos(0, height + GAP);
            add(controls);
            resize(width, (int) controls.bottom() + 2);
        }
    }

    private boolean isCurrent(ModParryRiposte buff) {
        return buff.target != null && ModParryRiposte.find(buff.target) == buff;
    }
}
