package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.spd.mod.mechanics.ModAssassinate;
import com.watabou.noosa.ui.Component;

/** Assassinate's live configuration window. */
public class WndAssassinBuffInfo extends WndInfoBuff {

    private static final int GAP = 3;
    private static final int CHECK_HEIGHT = 18;

    public WndAssassinBuffInfo(final ModAssassinate buff) {
        super(buff);

        final CheckBox check = new CheckBox("Assassinate") {
            @Override
            protected void onClick() {
                if (!valid(buff)) {
                    WndAssassinBuffInfo.this.hide();
                    return;
                }

                super.onClick();
                buff.toggleAssassin();
                checked(buff.assassinEnabled());
            }
        };
        check.checked(buff.assassinEnabled());

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

    private boolean valid(ModAssassinate buff) {
        return buff.target != null && ModAssassinate.find(buff.target) == buff;
    }
}
