package com.spd.mod.journal;

import com.watabou.noosa.Image;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;

public class ModGridBuff extends ModGridEntry {

    public Class<? extends Buff> buffClass;

    public ModGridBuff(Image image, Class<? extends Buff> buffClass,
                       String infoTitle, String infoDescription) {
        super(image, infoTitle, infoDescription);
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
