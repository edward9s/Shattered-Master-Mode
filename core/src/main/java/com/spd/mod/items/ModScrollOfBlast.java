package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfRetribution;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;

import com.spd.mod.mechanics.ModBlast;

public class ModScrollOfBlast extends Scroll {

    public ModScrollOfBlast() {
        super();
        this.icon = ItemSpriteSheet.Icons.SCROLL_PSIBLAST;
        this.unique = true;
    }

    @Override
    public String name() {
        return "Scroll of Blast";
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
        ModBlast.castBlast(Dungeon.hero);
    }

    @Override
    public String desc() {
        return "Hero will blast all visible mobs (_excludes_ Demon Spawner and Shopkeeper).";
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
        this.image = new ScrollOfRetribution().image;
        this.rune = "scroll_blast";
    }

    @Override
    public void setKnown() {
    }
}
