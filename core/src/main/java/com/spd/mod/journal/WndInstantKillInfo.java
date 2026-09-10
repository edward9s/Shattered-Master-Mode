package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.spd.mod.mechanics.ModInstantKill;
import com.watabou.noosa.ui.Component;

/** Instant Kill's live configuration window. */
public class WndInstantKillInfo extends WndInfoBuff {

    private static final int GAP = 3;
    private static final int CHECK_HEIGHT = 18;

    public WndInstantKillInfo(final ModInstantKill buff) {
        super(buff);

        final CheckBox instantKillCheck = new CheckBox("Instant Kill") {
            @Override
            protected void onClick() {
                if (!valid(buff)) {
                    WndInstantKillInfo.this.hide();
                    return;
                }

                super.onClick();
                buff.toggleInstantKill();
                checked(buff.instantKillEnabled());
                ModTotalInfoOverlay.refreshIndicators();
            }
        };
        instantKillCheck.checked(buff.instantKillEnabled());

        final Component controls = new Component() {
            @Override
            protected void layout() {
                instantKillCheck.setRect(x, y, width, CHECK_HEIGHT);
            }
        };
        controls.add(instantKillCheck);
        controls.setSize(width, CHECK_HEIGHT);

        if (!ModWindowCompat.addToBottom(this, controls, GAP, 2)) {
            controls.setPos(0, height + GAP);
            add(controls);
            resize(width, (int) controls.bottom() + 2);
        }
    }

    private boolean valid(ModInstantKill buff) {
        return buff.target != null && ModInstantKill.find(buff.target) == buff;
    }
}
