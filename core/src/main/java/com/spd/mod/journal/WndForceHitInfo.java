package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.spd.mod.mechanics.ModForceHit;
import com.watabou.noosa.ui.Component;

/** Force Hit's live configuration window. */
public class WndForceHitInfo extends WndInfoBuff {

    private static final int GAP = 3;
    private static final int CHECK_HEIGHT = 18;

    public WndForceHitInfo(final ModForceHit buff) {
        super(buff);

        final CheckBox check = new CheckBox("Force Hit") {
            @Override
            protected void onClick() {
                if (!valid(buff)) {
                    WndForceHitInfo.this.hide();
                    return;
                }

                super.onClick();
                buff.toggleForceHit();
                checked(buff.forceHitEnabled());
            }
        };
        check.checked(buff.forceHitEnabled());

        final Component controls = new Component() {
            @Override
            protected void layout() {
                check.setRect(x, y, width, CHECK_HEIGHT);
            }
        };
        controls.add(check);
        controls.setSize(width, CHECK_HEIGHT);

        if (!ModWindowCompat.addToBottom(this, controls, GAP, 2)) {
            controls.setPos(0, height + GAP);
            add(controls);
            resize(width, (int) controls.bottom() + 2);
        }
    }

    private boolean valid(ModForceHit buff) {
        return buff.target != null && ModForceHit.findAttached(buff.target) == buff;
    }
}
