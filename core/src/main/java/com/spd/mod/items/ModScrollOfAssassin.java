package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;

import com.spd.mod.mechanics.ModAssassin;

public class ModScrollOfAssassin extends Scroll {

    public ModScrollOfAssassin() {
        super();
        this.icon = ItemSpriteSheet.Icons.RING_ACCURACY; // 0
        this.unique = true;
        this.usesTargeting = true;
    }

    @Override
    public String name() {
        return "Scroll of Assassin";
    }

    @Override
    public String info() {
        return desc();
    }

    @Override
    public void execute(Hero hero, String action) {
        if ("READ".equals(action)) {
            doRead();
        } else {
            super.execute(hero, action);
        }
    }

    public void doRead() {
        ModAssassin.cast(Dungeon.hero);
    }

    @Override
    public String desc() {
        return "Hero will attempt to assssin an enemy or flash to anywhere.";
    }

    @Override
    public int value() {
        return 0;
    }

    @Override
    public boolean isIdentified() {
        return true;
    }

    @Override
    public boolean isKnown() {
        return true;
    }

    @Override
    public void reset() {
        this.image = new ScrollOfTeleportation().image;
        this.rune = "scroll_assassin";
    }

    @Override
    public void setKnown() {
    }
}
