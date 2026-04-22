package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfMagicMapping;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;

import com.spd.mod.mechanics.ModSight;

public class ModScrollOfSight extends Scroll {

    public ModScrollOfSight() {
        super();
        this.icon = ItemSpriteSheet.Icons.SCROLL_FORESIGHT;
        this.unique = true;
    }

    @Override
    public String name() {
        return "Scroll of Sight";
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
        ModSight.onSight(Dungeon.hero);
    }

    @Override
    public String desc() {
        return "Hero will reveal all hidden enemies, valuable treasures, and the exit of the current depth.";
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
        this.image = new ScrollOfMagicMapping().image;
        this.rune = "scroll_sight";
    }

    @Override
    public void setKnown() {
        // 阻斷系統註冊機制以防崩潰
    }
}
