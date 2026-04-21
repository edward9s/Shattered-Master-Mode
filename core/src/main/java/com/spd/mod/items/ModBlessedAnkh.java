package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.items.Ankh;

public class ModBlessedAnkh extends Ankh {

    public ModBlessedAnkh() {
        super();
        this.stackable = true;
        this.bless();
    }
}
