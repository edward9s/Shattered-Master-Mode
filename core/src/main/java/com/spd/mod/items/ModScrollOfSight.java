package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.ScrollHolder;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfMagicMapping;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.watabou.utils.Bundle;

import com.spd.mod.mechanics.ModSight;

public class ModScrollOfSight extends Scroll implements ModReusable {

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
        this.icon = ItemSpriteSheet.Icons.SCROLL_FORESIGHT;
        this.rune = "scroll_sight";
        this.stackable = false;
        this.keptThoughLostInvent = true;
        this.unique = true;
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        this.level(0);
        reset();
    }

    @Override
    public String name() {
        return "Scroll of Sight";
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
    public void setKnown() {
        // 阻斷系統註冊機制以防崩潰
    }
}
