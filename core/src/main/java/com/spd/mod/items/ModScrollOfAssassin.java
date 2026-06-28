package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.watabou.utils.Bundle;

import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import java.util.Collections;

import com.spd.mod.mechanics.ModAssassin;

public class ModScrollOfAssassin extends Scroll {

    public ModScrollOfAssassin() {
        super();
        this.level(1);
        this.icon = ItemSpriteSheet.Icons.RING_ACCURACY; // 0
        this.stackable = false;
        this.keptThoughLostInvent = true;
        this.unique = true;
        this.usesTargeting = true;
    }
    
    @Override
    public boolean keptThroughLostInventory() {
        return true;
    }
    
    @Override
    public void reset() {
        super.reset();
        this.image = new ScrollOfTeleportation().image;
        this.rune = "scroll_assassin";
        this.keptThoughLostInvent = true;
    }
    
    @Override
    public void restoreFromBundle(Bundle bundle) {
        // 在讀取存檔前將等級歸零，抵消建構子的預設值，避免 Item 原生機制的 upgrade 疊加
        this.level(0);
        super.restoreFromBundle(bundle);
        this.keptThoughLostInvent = true;
    }

    @Override
    public String name() {
        return "Scroll of Assassin";
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
    public void setKnown() {
        // 阻斷系統註冊機制以防崩潰
    }
    
    @Override
    protected void onDetach() {
        super.onDetach();
        
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String className = element.getClassName();
            String methodName = element.getMethodName();
            
            if (("die".equals(methodName) && className.endsWith("Hero")) || 
                className.endsWith("WndResurrect")) {
                if (Dungeon.hero != null && Dungeon.hero.belongings != null && Dungeon.hero.belongings.backpack != null) {
                    Bag backpack = Dungeon.hero.belongings.backpack;
                    if (!backpack.items.contains(this)) {
                        backpack.items.add(this);
                        Collections.sort(backpack.items, Item.itemComparator);
                        Item.updateQuickslot();
                    }
                }
                break;
            }
        }
    }
}
