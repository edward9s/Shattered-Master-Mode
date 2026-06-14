package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfMagicMapping;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.watabou.utils.Bundle;

import com.spd.mod.mechanics.ModSight;

public class ModScrollOfSight extends Scroll {

    public ModScrollOfSight() {
        super();
        this.icon = ItemSpriteSheet.Icons.SCROLL_FORESIGHT;
        this.stackable = false;
        this.unique = true;
        this.level(1);
    }
    
    @Override
    public void restoreFromBundle(Bundle bundle) {
        // 在讀取存檔前將等級歸零，抵消建構子的預設值，避免 Item 原生機制的 upgrade 疊加
        this.level(0);
        super.restoreFromBundle(bundle);
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
