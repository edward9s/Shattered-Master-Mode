package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.noosa.Image;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;

public class ModGridBuff extends ScrollingGridPane.GridItem {

    public Class<? extends Buff> buffClass;

    public ModGridBuff(Image image, Class<? extends Buff> buffClass) {
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
