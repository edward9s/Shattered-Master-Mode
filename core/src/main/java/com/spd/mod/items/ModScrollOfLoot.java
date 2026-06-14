package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTransmutation;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.watabou.utils.Bundle;

import com.spd.mod.mechanics.ModLoot;

public class ModScrollOfLoot extends Scroll {

    public ModScrollOfLoot() {
        super();
        this.icon = ItemSpriteSheet.Icons.RING_WEALTH; // 11
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
        return "Scroll of Loot";
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
        ModLoot.grabItems();
        ModLoot.trampleGrass();
        ModLoot.collectHeaps();
    }

    @Override
    public String desc() {
        return "Hero will trample all high-grass and loot all heaps.";
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
        this.image = new ScrollOfTransmutation().image;
        this.rune = "scroll_loot";
    }

    @Override
    public void setKnown() {
        // 阻斷系統註冊機制以防崩潰
    }
}
