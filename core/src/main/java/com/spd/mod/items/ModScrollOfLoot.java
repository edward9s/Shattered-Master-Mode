package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTransmutation;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;

import java.util.ArrayList;

import com.spd.mod.mechanics.ModLoot;

public class ModScrollOfLoot extends Scroll {

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

    /** 供 WndModLoot 讀取目前暫存清單(回傳實際的 live list,視窗只讀不直接增刪)。 */
    public ArrayList<Item> getStored() {
        return stored;
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
    public void execute(Hero hero, String action) {
        if ("READ".equals(action)) {
            // 參考 TimekeepersHourglass:讀卷軸不直接動作,先彈出 WndOptions 三選一
            showLootMenu(hero);
        } else {
            super.execute(hero, action);
        }
    }

    /**
     * 卷軸的主選單。四個按鈕分別對應四個 action:
     *  - Loot → doRead():全地圖收割 + 把放不下的道具吸進卷軸
     *  - Put  → showPutSelector():從背包挑選道具收進卷軸(可連續挑選多件)
     *  - Take → 開啟 WndModLoot:從卷軸內逐件挑選收回背包 / 丟棄
     *  - Dump → doDump():把卷軸內的道具一次倒乾淨
     * 沒有暫存道具時,Take / Dump 兩顆會被禁用;Loot / Put 永遠可用。
     */
    private void showLootMenu(final Hero hero) {
        final boolean hasStored = !stored.isEmpty();
        final int count = stored.size();

        String message = hasStored
                ? "Loot all heaps on this floor, store backpack items in the scroll, or retrieve the " + count + " item(s) held inside."
                : "Loot all heaps on this floor, or store backpack items in the scroll for safekeeping.";

        GameScene.show(new WndOptions(
                new ItemSprite(this),
                name(),
                message,
                "Loot",
                "Put",
                "Take (" + count + ")",
                "Dump (" + count + ")") {

            @Override
            protected void onSelect(int index) {
                switch (index) {
                    case 0:
                        doRead();
                        break;
                    case 1:
                        showPutSelector();
                        break;
                    case 2:
                        if (!stored.isEmpty()) {
                            GameScene.show(new WndModLoot(ModScrollOfLoot.this));
                        }
                        break;
                    case 3:
                        doDump(hero);
                        break;
                }
            }

            @Override
            protected boolean enabled(int index) {
                // Loot / Put 永遠可用;Take / Dump 需要有暫存道具
                return index == 0 || index == 1 || hasStored;
            }
        });
    }

    /**
     * Put:從背包挑選道具收進卷軸。
     * 比照 BtnIdentify → ModItemIdentify 的選物模式:用 GameScene.selectItem 開啟 WndBag,
     * 選到道具後立刻執行 putSingle(),再呼叫自己重新開啟選物視窗,達成「連續點擊收件」的效果
     * (WndBag 預設 hideAfterSelecting()=true,所以每次選擇都是「先關閉、再立刻重開」的視覺效果)。
     * 按返回鍵 / 點外側關閉時 onSelect 會收到 null,直接結束,不重開。
     */
    private void showPutSelector() {
        GameScene.selectItem(new WndBag.ItemSelector() {
            @Override
            public String textPrompt() {
                return "Select an item to store";
            }

            @Override
            public boolean itemSelectable(Item item) {
                // 只排除卷軸自己(不能把自己放進自己)。裝備中的道具可以選,
                // putSingle 會先嘗試卸裝(對齊 WndTradeItem.sell() 的慣例),卸不掉(例如被詛咒鎖住)就直接放棄這次操作。
                return item != ModScrollOfLoot.this;
            }

            @Override
            public void onSelect(Item item) {
                if (item == null) {
                    return;
                }
                putSingle(Dungeon.hero, item);
                showPutSelector();
            }
        });
    }

    public void doRead() {
        Hero hero = Dungeon.hero;

        // ModLoot 負責全地圖(它會跳過英雄腳下那格,避免背包滿時 drop/collect 互踩的無限迴圈)。
        // grab/collect 回傳「真正進背包(會播 ITEM 撿取音效)」的件數,供下方決定是否補播。
        int pickedIntoBags = ModLoot.grabItems();
        ModLoot.trampleGrass();
        pickedIntoBags += ModLoot.collectHeaps();

        // 全地圖 loot 完後,卷軸只負責「英雄腳下」這一格剩餘的 Heap。
        // 職責與 ModLoot 互不重疊,不會有同一個 Heap 被兩套規則處理的問題。
        int absorbed = absorbUnderfoot(hero);
        if (absorbed > 0) {
            GLog.i("Absorbed " + absorbed + " item(s) into the scroll.");
            // 只有在整輪 grab+collect 都沒有任何一件進背包(因此沒播過 ITEM 音效)時,
            // 才為吸入動作補播一次清脆音效;否則交給原生撿取音效,避免重疊。
            if (pickedIntoBags == 0) {
                Sample.INSTANCE.play(Assets.Sounds.ITEM);
            }
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
            absorb(item);
            heap.items.remove(item);
            count++;
        }
        if (heap.items.isEmpty()) {
            heap.destroy();
        }
        return count;
    }

    /**
     * 把單一道具併入 stored:可堆疊者先在既有清單找同類合併,否則作為新項目加入。
     * 對齊 Item.collect() 的原生堆疊行為:用 isSimilar 判同類、用 merge 把數量相加
     * (因此會沿用各道具自己的 merge 規則,例如 Dewdrop 把數量上限鎖在 1)。
     */
    private void absorb(Item item) {
        if (item.stackable) {
            for (Item existing : stored) {
                if (existing.isSimilar(item)) {
                    existing.merge(item);   // existing.quantity += item.quantity; item.quantity = 0
                    return;
                }
            }
        }
        stored.add(item);
    }

    /**
     * 收回單一指定道具(供 WndModLoot 的 Take 流程逐件呼叫)。
     * 先走官方 collect 進背包/合適的原生袋子;放不下就 drop 到英雄腳下。
     * 無論哪種結果,該道具都會離開 stored。
     */
    public boolean takeSingle(Hero hero, Item item) {
        if (item == null || !stored.contains(item)) {
            return false;
        }

        if (item.collect(hero.belongings.backpack)) {
            stored.remove(item);
            GLog.i("Took " + item.name() + " from the scroll.");
        } else {
            Dungeon.level.drop(item, hero.pos).sprite.drop();
            stored.remove(item);
            GLog.w("Dropped " + item.name() + " on the floor (backpack full).");
        }

        Sample.INSTANCE.play(Assets.Sounds.ITEM);
        syncCount();
        Item.updateQuickslot();
        return true;
    }

    /**
     * 收進單一指定道具(供 Put 選物視窗逐件呼叫)。
     * 裝備中的道具先嘗試卸裝(對齊 WndTradeItem.sell()/WndEnergizeItem 的慣例),
     * 卸不掉(例如被詛咒鎖住)就直接放棄,不收進卷軸。
     * 成功後用 detachAll 把整疊從背包(或其巢狀子袋,例如箭袋/聖水瓶)移除,
     * 再走 absorb() 併入 stored,沿用既有的 isSimilar/merge 堆疊規則。
     * detachAll 不分數量,一律整疊移除,對齊 WndTradeItem.sell() 的整疊處理方式。
     */
    public boolean putSingle(Hero hero, Item item) {
        if (item == null || item == this) {
            return false;
        }

        if (item.isEquipped(hero) && !((EquipableItem) item).doUnequip(hero, false)) {
            GLog.w("Can't unequip " + item.name() + ".");
            return false;
        }

        item.detachAll(hero.belongings.backpack);
        absorb(item);
        GLog.i("Stored " + item.name() + " in the scroll.");
        Sample.INSTANCE.play(Assets.Sounds.ITEM);

        syncCount();
        Item.updateQuickslot();
        return true;
    }

    /**
     * 強制清空:先盡量 collect 回背包,放不下的 drop 到英雄腳下。
     * 結束後 stored 保證為空。用於 Dump 按鈕,以及處置卷軸(賣出/煉化)前一次倒乾淨。
     */
    private void doDump(Hero hero) {
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
            GLog.w("Dumped " + released + " item(s) into your bags; " + dropped + " dropped on the floor (backpack full).");
        } else if (released > 0) {
            GLog.i("Dumped " + released + " item(s) into your bags.");
        }
        if (released + dropped > 0) {
            Sample.INSTANCE.play(Assets.Sounds.ITEM);
        }
        syncCount();
    }

    @Override
    public String desc() {
        String base = "Hero will trample all high-grass and loot all heaps."
                + " Items that don't fit in your bags are absorbed into the scroll."
                + " You can also Put items from your bags into the scroll for safekeeping.";
        if (!stored.isEmpty()) {
            base += "\n\nCurrently holding " + stored.size() + " item(s). Use Take to retrieve them one at a time, or Dump to empty the scroll at once.";
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
