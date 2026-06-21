package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTransmutation;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;

import java.util.ArrayList;

import com.spd.mod.mechanics.ModLoot;

public class ModScrollOfLoot extends Scroll {

    public static final String AC_RELEASE = "RELEASE";
    public static final String AC_RELEASE_ALL = "RELEASE_ALL";

    // 真實資料來源:暫存的溢出道具。level() 只是它的數量鏡像,不是資料本身。
    private ArrayList<Item> stored = new ArrayList<>();

    private static final String STORED = "stored";

    public ModScrollOfLoot() {
        super();
        this.level(0);
        this.icon = ItemSpriteSheet.Icons.RING_WEALTH; // 11
        this.stackable = false;
        this.keptThoughLostInvent = true;
        this.unique = true;
    }

    @Override
    public boolean keptThroughLostInventory() {
        return true;
    }

    @Override
    public void reset() {
        super.reset();
        this.image = new ScrollOfTransmutation().image;
        this.rune = "scroll_loot";
        this.keptThoughLostInvent = true;
    }

    /** 數量顯示:把 stored 的數量同步到 level,讓卷軸右下角顯示數字。 */
    private void syncCount() {
        this.level(stored.size());
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(STORED, stored);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        // 在讀取存檔前將等級歸零，抵消建構子的預設值，避免 Item 原生機制的 upgrade 疊加
        this.level(0);
        super.restoreFromBundle(bundle);
        this.keptThoughLostInvent = true;

        stored = new ArrayList<>();
        for (Bundlable b : bundle.getCollection(STORED)) {
            stored.add((Item) b);
        }
        syncCount();
    }

    @Override
    public String name() {
        return "Scroll of Loot";
    }

    @Override
    public ArrayList<String> actions(Hero hero) {
        ArrayList<String> actions = super.actions(hero);
        if (!stored.isEmpty()) {
            if (!actions.contains(AC_RELEASE)) {
                actions.add(AC_RELEASE);
            }
            if (!actions.contains(AC_RELEASE_ALL)) {
                actions.add(AC_RELEASE_ALL);
            }
        }
        return actions;
    }

    @Override
    public String actionName(String action, Hero hero) {
        if (AC_RELEASE.equals(action)) {
            return "Release (" + stored.size() + ")";
        }
        if (AC_RELEASE_ALL.equals(action)) {
            return "Release All (" + stored.size() + ")";
        }
        return super.actionName(action, hero);
    }

    @Override
    public void execute(Hero hero, String action) {
        if ("READ".equals(action)) {
            doRead();
        } else if (AC_RELEASE.equals(action)) {
            doRelease(hero);
        } else if (AC_RELEASE_ALL.equals(action)) {
            doReleaseAll(hero);
        } else {
            super.execute(hero, action);
        }
    }

    public void doRead() {
        Hero hero = Dungeon.hero;

        // ModLoot 負責全地圖(它會跳過英雄腳下那格,避免背包滿時 drop/collect 互踩的無限迴圈)
        ModLoot.grabItems();
        ModLoot.trampleGrass();
        ModLoot.collectHeaps();

        // 全地圖 loot 完後,卷軸只負責「英雄腳下」這一格剩餘的 Heap。
        // 職責與 ModLoot 互不重疊,不會有同一個 Heap 被兩套規則處理的問題。
        int absorbed = absorbUnderfoot(hero);
        if (absorbed > 0) {
            GLog.i("Absorbed " + absorbed + " item(s) into the scroll.");
        }
        syncCount();
    }

    /** 只處理英雄腳下那一格的 Heap,把撿不進背包的道具吸進 stored。 */
    private int absorbUnderfoot(Hero hero) {
        if (Dungeon.level == null || Dungeon.level.heaps == null || hero == null) return 0;

        Heap heap = Dungeon.level.heaps.get(hero.pos);
        if (heap == null || heap.isEmpty()) return 0;
        // 共用 ModLoot 的型別判斷,避免兩份清單不一致
        if (!ModLoot.Collect.isCollectable(heap.type)) return 0;

        int count = 0;
        for (Item item : heap.items.toArray(new Item[0])) {
            if (item == null) continue;
            stored.add(item);
            heap.items.remove(item);
            count++;
        }
        if (heap.items.isEmpty()) {
            heap.destroy();
        }
        return count;
    }

    /**
     * 把暫存的道具收回背包,先進先出(FIFO):先吸收的先放回。
     * 放不下就停(剩下的留在卷軸),清空也停。
     */
    private void doRelease(Hero hero) {
        int released = 0;

        // 從開頭取,實現 FIFO。成功才 remove(0),失敗立即停止。
        while (!stored.isEmpty()) {
            Item item = stored.get(0);

            // collect 走官方標準路徑,自然會優先塞進背包/合適的原生袋子;
            // 體驗上跟正常撿東西完全一致,不違和。
            if (item.collect(hero.belongings.backpack)) {
                stored.remove(0);
                released++;
            } else {
                // 背包(連同原生袋子)都滿了,停止釋放,剩下的留著
                GLog.w("Backpack full. " + stored.size() + " item(s) still in the scroll.");
                break;
            }
        }

        if (released > 0) {
            GLog.i("Released " + released + " item(s).");
        }
        syncCount();
    }

    /**
     * 強制清空:先盡量 collect 回背包,放不下的 drop 到英雄腳下。
     * 結束後 stored 保證為空。用於處置卷軸(賣出/煉化)前一次倒乾淨。
     */
    private void doReleaseAll(Hero hero) {
        int released = 0;
        int dropped = 0;

        for (Item item : stored.toArray(new Item[0])) {
            if (item.collect(hero.belongings.backpack)) {
                released++;
            } else {
                Dungeon.level.drop(item, hero.pos).sprite.drop();
                dropped++;
            }
        }
        stored.clear();

        if (dropped > 0) {
            GLog.w("Released " + released + " item(s); dropped " + dropped + " on the floor (backpack full).");
        } else if (released > 0) {
            GLog.i("Released " + released + " item(s).");
        }
        syncCount();
    }

    @Override
    public String desc() {
        String base = "Hero will trample all high-grass and loot all heaps."
                + " Items that don't fit in your bags are absorbed into the scroll.";
        if (!stored.isEmpty()) {
            base += "\n\nCurrently holding " + stored.size() + " item(s). Use Release to retrieve them.";
        }
        return base;
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
