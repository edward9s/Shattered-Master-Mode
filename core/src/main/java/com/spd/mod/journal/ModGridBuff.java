package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.noosa.Image;

public class ModGridBuff extends ScrollingGridPane.GridItem {

    public Class<?> buffClass;

    public ModGridBuff(Image image, Class<?> buffClass) {
        super(image);
        this.buffClass = buffClass;
    }

    @Override
    public boolean onClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ModCharSelector.start(this.buffClass);
        return true;
    }
}
