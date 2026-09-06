package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.ScrollHolder;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfRetribution;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.watabou.utils.Bundle;

import java.util.ArrayList;
import java.util.Collections;

import com.spd.mod.mechanics.ModBlast;

public class ModScrollOfBlast extends Scroll implements ModReusable {

    public static final String AC_INSTANT_KILL = "INSTANT_KILL";
    private static final String INSTANT_KILL = "instant_kill";

    private boolean instantKill;

    @Override
    public boolean keptThroughLostInventory() {
        return true;
    }

    /** 拒絕被收進捲軸筒,讓 Item.collect() 的自動分袋流程改把卷軸放進主背包。 */
    @Override
    public boolean collect(Bag container) {
        if (container instanceof ScrollHolder) {
            return false;
        }
        return super.collect(container);
    }

    @Override
    public void reset() {
        super.reset();
        this.image = ItemSpriteSheet.SCROLL_HOLDER;
        this.icon = ItemSpriteSheet.Icons.SCROLL_PSIBLAST;
        this.rune = "scroll_blast";
        this.stackable = false;
        this.keptThoughLostInvent = true;
        this.unique = true;
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(INSTANT_KILL, instantKill);
    }
    
    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        instantKill = bundle.getBoolean(INSTANT_KILL);
        // Older saves stored these reusable mod scrolls at +1. They are not
        // upgradeable tools, so normalize them to level 0 on load.
        this.level(0);
        reset();
    }

    @Override
    public String name() {
        return "Scroll of Blast";
    }

    @Override
    public ArrayList<String> actions(Hero hero) {
        ArrayList<String> actions = super.actions(hero);
        if (!actions.contains(AC_INSTANT_KILL)) {
            actions.add(AC_INSTANT_KILL);
        }
        return actions;
    }

    @Override
    public String actionName(String action, Hero hero) {
        if (AC_INSTANT_KILL.equals(action)) {
            return instantKill ? "Instant Kill: ON" : "Instant Kill: OFF";
        }
        return super.actionName(action, hero);
    }

    @Override
    public void execute(Hero hero, String action) {
        if ("READ".equals(action)) {
            doRead();
        } else if (AC_INSTANT_KILL.equals(action)) {
            instantKill = !instantKill;
        } else {
            super.execute(hero, action);
        }
    }

    public void doRead() {
        ModBlast.castBlast(Dungeon.hero, instantKill);
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
    public void setKnown() {
        // 阻斷系統註冊機制以防崩潰
    }
}
