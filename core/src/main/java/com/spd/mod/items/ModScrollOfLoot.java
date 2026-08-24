package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.ScrollHolder;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTransmutation;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;

import java.util.ArrayList;

import com.spd.mod.mechanics.ModItemOrder;
import com.spd.mod.mechanics.ModLoot;

public class ModScrollOfLoot extends Scroll {

    // 真實資料來源:暫存的溢出道具。level() 只是它的數量鏡像,不是資料本身。
    private ArrayList<Item> stored = new ArrayList<>();

    // 為了發動而借去背包的道具(見 useSingle)。用完會被消耗的東西自己就不見了,
    // 不會被消耗的(法杖、部分神器)則會留在背包,下次讀卷軸時由 reclaimLent() 收回來。
    // 刻意不進存檔:道具本身已經在背包裡、由背包負責保存,重開遊戲後就不再追蹤。
    private final ArrayList<Item> lent = new ArrayList<>();

    private static final String STORED = "stored";

    public ModScrollOfLoot() {
        super();
        this.level(0);
    }

    @Override
    public boolean keptThroughLostInventory() {
        return true;
    }

    /**
     * 拒絕被收進捲軸筒:Item.collect() 的自動分袋流程會先問每個子袋 collect,
     * 這裡回傳 false 讓流程跳過 ScrollHolder,繼續往下把卷軸直接放進主背包。
     * 舊存檔若卷軸已在筒內也不會遺失:Bag.restoreFromBundle 對 collect 失敗的道具會強制塞回原袋。
     */
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
        this.icon = ItemSpriteSheet.Icons.RING_WEALTH;
        this.rune = "scroll_loot";
        this.stackable = false;
        this.keptThoughLostInvent = true;
        this.unique = true;
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

        stored = new ArrayList<>();
        for (Bundlable b : bundle.getCollection(STORED)) {
            stored.add((Item) b);
        }
        // 舊存檔是照吸入順序存的,讀進來後一併套上目前的排序規則
        sortStored();
        syncCount();
        reset();
    }

    @Override
    public String name() {
        return "Scroll of Loot";
    }

    @Override
    public void execute(Hero hero, String action) {
        if ("READ".equals(action)) {
            // 讀卷軸不直接動作,開啟主視窗:上方 2x2 功能鍵(Loot/Put/Take/Dump),
            // 下方可捲動地列出卷軸內所有「有預設主動功能」的物品,點擊即發動。
            reclaimLent(hero);
            GameScene.show(new WndModLoot(this, WndModLoot.Mode.USE));
        } else {
            super.execute(hero, action);
        }
    }

    /**
     * 可以收進卷軸的道具。
     *  - 卷軸自己:不能把自己放進自己
     *  - ModAnkh:它靠留在背包裡的 onDetach/復活流程運作,收進卷軸就等於失效,一律禁止
     */
    public static boolean canStore(Item item) {
        return item != null
                && !(item instanceof ModScrollOfLoot)
                && !(item instanceof ModAnkh);
    }

    /**
     * 把「借去背包發動、結果沒被消耗」的道具收回卷軸(法杖、部分神器)。
     *
     * 為什麼不是在 execute() 一回來就收:那時動作往往還沒真的發生。投擲武器、法杖、位移卷軸
     * 都是 execute() 裡呼叫 GameScene.selectCell() 後立刻回傳,真正的使用在玩家點目標格時才發生;
     * 未鑑定的藥水/卷軸也會先跳確認視窗。這時候把東西抽回卷軸,原生流程稍後的 detach 就會落空
     * ——投擲武器會變成「地上一件、卷軸裡也還有一件」,藥水會變成喝不完。
     * 而「動作結束了沒」沒有可靠的通用信號可查,所以改在下一次讀卷軸這個必定安全的時間點處理。
     *
     * 判斷方式只有一條:那件道具是否原封不動地還在身上。被喝掉/丟掉/併進別疊的都不會通過,
     * 所以不會誤收玩家自己的東西。
     */
    private void reclaimLent(Hero hero) {
        if (lent.isEmpty()) {
            return;
        }
        if (hero == null || hero.belongings == null) {
            lent.clear();
            return;
        }

        boolean reclaimed = false;
        for (Item item : lent.toArray(new Item[0])) {
            if (item.quantity() > 0 && hero.belongings.contains(item)) {
                item.detachAll(hero.belongings.backpack);
                absorb(item);
                GLog.i("Returned " + item.name() + " to the scroll.");
                reclaimed = true;
            }
        }
        lent.clear();

        if (reclaimed) {
            sortStored();
            syncCount();
            Item.updateQuickslot();
        }
    }

    /**
     * 依 {@link ModItemOrder} 重排暫存清單:Mod 物品 → 魔法袋 → 藥劑筒 → 卷軸筒 → 絨布袋 → 其他,
     * 同組內用遊戲原生的類別順序。每次收東西進來(Loot 吸入 / Put)與讀檔後都會重排,
     * 讓 Take 視窗的順序永遠是穩定且與主視窗一致的。
     */
    private void sortStored() {
        ModItemOrder.sort(stored);
    }

    /**
     * Put:從背包挑選道具收進卷軸。
     * 比照 BtnIdentify → ModItemIdentify 的選物模式:用 GameScene.selectItem 開啟 WndBag,
     * 選到道具後立刻執行 putSingle(),再呼叫自己重新開啟選物視窗,達成「連續點擊收件」的效果
     * (WndBag 預設 hideAfterSelecting()=true,所以每次選擇都是「先關閉、再立刻重開」的視覺效果)。
     * 按返回鍵 / 點外側關閉時 onSelect 會收到 null,直接結束,不重開。
     */
    void showPutSelector() {
        GameScene.selectItem(new WndBag.ItemSelector() {
            @Override
            public String textPrompt() {
                return "Select an item to store";
            }

            @Override
            public boolean itemSelectable(Item item) {
                // 排除卷軸自己與 ModAnkh(見 canStore)。裝備中的道具可以選,
                // putSingle 會先嘗試卸裝(對齊 WndTradeItem.sell() 的慣例),卸不掉(例如被詛咒鎖住)就直接放棄這次操作。
                return canStore(item);
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
            sortStored();
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
            // 不能收進卷軸的東西(ModAnkh)留在原地,heap 因此可能不會清空,下面已處理
            if (!canStore(item)) continue;
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
     * 直接使用卷軸內的一件道具(供主視窗的物品格呼叫)。
     *
     * 使用效果一律交給道具自己的 execute() 去跑,本檔案不複製任何一種道具的使用邏輯;
     * 這裡只負責「讓原生流程能正確作用在卷軸內的道具上」。
     *
     * 除了 {@link ModReusable}(用完不會被消耗,就地發動即可)以外,一律先把「一件」放進背包
     * 再發動,因為原生實作對背包有兩種依賴,而且無法從外面判斷某個道具吃到哪一種:
     *  - 扣數量:Item.detach() 只剩一件時走 detachAll(背包),在背包裡找不到就不會移除,
     *    動作照常發動、數量卻沒少,等於變成用不完的無限道具。
     *  - 前置檢查:Item.execute() 對 AC_THROW / AC_DROP 明文要求 backpack.contains(this),
     *    不在背包裡就整段跳過——投擲武器、符石、種子會「丟不出去」。
     *
     * 也不自己扣數量,因為無法預先判斷某個動作到底會不會消耗道具(Mod 卷軸、法杖就不會):
     * 自己扣會變成該留的被扣掉,或是連同原生流程扣兩次。
     *
     * 疊在一起的道具只借出一件,其餘留在卷軸裡。
     */
    public boolean useSingle(Hero hero, Item item) {
        if (hero == null || item == null || !stored.contains(item)) {
            return false;
        }

        if (item instanceof ModReusable) {
            item.execute(hero);
            return true;
        }

        // split(1) 在「整疊只有一件」時回傳 null,代表整件借出去
        Item single = item.split(1);
        boolean whole = (single == null);
        if (whole) {
            single = item;
        }

        // collect() 會順便把它併進背包裡既有的同一疊、或放進合適的子袋(藥劑筒之類)
        if (!single.collect(hero.belongings.backpack)) {
            // 背包滿:直接塞進背包清單。容量只在 collect() 時檢查,之後不論是
            // Bag.contains()(投擲的前置檢查)或 detachAll()(扣數量)都只認
            // 「這件東西在不在清單裡」,所以照樣正常運作;用掉之後它就自己離開了。
            hero.belongings.backpack.items.add(single);
        }

        if (whole || item.quantity() <= 0) {
            stored.remove(item);
        }
        // 記下借出去的那一件:沒被消耗的話,下次讀卷軸時 reclaimLent() 會把它收回來
        lent.add(single);
        syncCount();
        Item.updateQuickslot();

        // collect() 可能把它併進背包裡既有的同類道具,此時 single 數量已歸零,改對那一疊執行
        Item target = single;
        if (target.quantity() <= 0 || !hero.belongings.contains(target)) {
            target = hero.belongings.getSimilar(single);
        }
        if (target == null) {
            return false;
        }

        target.execute(hero);
        return true;
    }

    /**
     * 收進單一指定道具(供 Put 選物視窗逐件呼叫)。
     * 裝備中的道具先嘗試卸裝(對齊 WndTradeItem.sell()/WndEnergizeItem 的慣例),卸不掉(例如被詛咒鎖住)就直接放棄,不收進卷軸。
     * 成功後用 detachAll 把整疊從背包(或其巢狀子袋,例如箭袋/聖水瓶)移除,
     * 再走 absorb() 併入 stored,沿用既有的 isSimilar/merge 堆疊規則。
     * detachAll 不分數量,一律整疊移除,對齊 WndTradeItem.sell() 的整疊處理方式。
     */
    public boolean putSingle(Hero hero, Item item) {
        if (!canStore(item)) {
            if (item instanceof ModAnkh) {
                GLog.w(item.name() + " can't be stored in the scroll.");
            }
            return false;
        }

        if (item.isEquipped(hero) && !((EquipableItem) item).doUnequip(hero, false)) {
            GLog.w("Can't unequip " + item.name() + ".");
            return false;
        }

        item.detachAll(hero.belongings.backpack);
        absorb(item);
        sortStored();
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
    void doDump(Hero hero) {
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
        String base = "Reading it opens a panel of the items held inside that have a default action,"
                + " so you can eat, read, drink or zap them straight out of the scroll."
                + "\n\nLoot makes the hero trample all high-grass and loot all heaps."
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
