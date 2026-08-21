package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.InventorySlot;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoItem;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Game;
import com.watabou.noosa.Image;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.PointF;

import java.util.ArrayList;

import com.spd.mod.mechanics.ModItemKind;

/**
 * 視覺上重刻 WndBag(含 InventorySlot 等級的格子精緻度),差別在於整個物品區可以「垂直捲動」。
 * 同一個視窗有兩種模式(見 {@link Mode}),差別只在「格子裡列什麼」與「點下去做什麼」,
 * 捲動、長按看資訊、格子池刷新等機制完全共用。
 *
 * 互動規則(對齊使用者需求):
 *  - USE 模式點擊有物品的格子 → 關窗後直接使用卷軸內的那件道具(吃/讀/喝/瞄準…)。
 *  - TAKE 模式點擊有物品的格子 → 透過 ModScrollOfLoot.takeSingle() 把它 collect 回背包,
 *    背包滿則 drop 到英雄腳下。動作後就地刷新清單,並保留捲動位置。
 *  - 長按(或 PC 上右鍵)有物品的格子 → 顯示該物品的資訊視窗(WndInfoItem),不觸發動作。
 *  - 點擊「空格子」或視窗內任何位置 → 不關閉(因為都落在 chrome 範圍內,Window.blocker 不會觸發)。
 *  - 點擊視窗「外」 → 由 Window 內建的 blocker 關閉。
 *
 * 點擊派發採用 ScrollPane 既有慣用法(同 ScrollingGridPane / ScrollingListPane):
 * 由 PointerController 統一接管指標輸入,只把「乾淨的點擊」轉成內容座標丟給 pane.onClick(),
 * 再手動命中測試到對應格子。InventorySlot 只負責「畫」,不靠自己的按鈕事件。
 */
public class WndModLoot extends Window {

    public enum Mode {
        /** 讀卷軸開啟:標題下方固定 2x2 功能鍵,格子列出卷軸內「有預設動作」的物品,點擊即發動。 */
        USE,
        /** Take 開啟:格子列出卷軸內暫存的所有物品,點擊收回背包。 */
        TAKE
    }

    private static final int NCOLS      = 5;   // 對齊 WndBag 的 COLS_P / COLS_L
    private static final int SLOT_BASE  = 28;  // 對齊 WndBag 的 SLOT_WIDTH/HEIGHT
    private static final int SLOT_MARGIN = 1;
    private static final int TITLE_HEIGHT = 14;
    private static final int BTN_HEIGHT = 16;  // 2x2 功能鍵每一列的高度
    private static final int BTN_MARGIN = 1;
    // 畫面上下兩端是遊戲介面：上方狀態列 38px、下方工具列 26px（大介面模式還有背包欄）。
    // Window 是垂直置中的，所以這個總量會被上下平分：留 100 代表上下各 50，兩端的
    // 介面按鈕都不會被蓋到。數字對齊原生 WndQuickBag 的 uiCamera.height - 100（同樣是在
    // 遊戲畫面內開的物品視窗）。
    private static final int UI_RESERVE_VER = 100;

    private final ModScrollOfLoot scroll;
    private final Mode mode;

    /** 目前格子要呈現的清單。TAKE 用卷軸的 live list,USE 用背包的快照。 */
    private ArrayList<Item> items;

    private LootPane pane;
    private int paneX, paneY, paneW, paneH;
    private int slotSize;

    // 暫存目前的捲動位置。用 static 讓它跨「關閉→重新打開視窗」保存
    // (每次開啟都是 new 一個新 WndModLoot,實例欄位會歸零;對齊 journal 分頁的 static scrollTop 做法)。
    // 兩種模式列的是完全不同的清單,捲動位置各記各的。
    private static float lastScrollYUse = 0f;
    private static float lastScrollYTake = 0f;

    // 上一幀的視窗位置,用來偵測視窗被移動(見 update() / offset())
    private float lastCamX = Float.NaN;
    private float lastCamY = Float.NaN;

    public WndModLoot(ModScrollOfLoot scroll) {
        this(scroll, Mode.TAKE);
    }

    public WndModLoot(ModScrollOfLoot scroll, Mode mode) {
        super();
        this.scroll = scroll;
        this.mode = mode;

        slotSize = SLOT_BASE;
        int windowWidth = slotSize * NCOLS + SLOT_MARGIN * (NCOLS - 1);

        // 直向螢幕太窄時,跟 WndBag 一樣把格子縮一點以塞進寬度(垂直方向交給捲動,不縮)
        if (!PixelScene.landscape()) {
            while (slotSize >= 26 && (windowWidth + chrome.marginHor()) > PixelScene.uiCamera.width) {
                slotSize--;
                windowWidth -= NCOLS;
            }
        }

        items = collectItems();

        // 表頭 = 標題列(+ USE 模式的兩列功能鍵)。功能鍵固定在表頭,不隨物品區捲動。
        int headerHeight = TITLE_HEIGHT;
        if (mode == Mode.USE) {
            headerHeight += 2 * (BTN_HEIGHT + BTN_MARGIN);
        }

        // 內容高度(含補滿最後一列的空格子),以及可視區高度上限
        int rows = Math.max(1, (int) Math.ceil(items.size() / (float) NCOLS));
        int contentHeight = rows * slotSize + (rows - 1) * SLOT_MARGIN;

        // 可視區上限：整個視窗（含外框）不超過 uiCamera.height - UI_RESERVE_VER，
        // 不足的部分交給捲動，不再往上下擴張去蓋住狀態列/工具列。
        int maxWindowHeight = PixelScene.uiCamera.height - UI_RESERVE_VER - chrome.marginVer();
        // 至少看得到一列格子：極小畫面（或 USE 模式表頭已經很高）時寧可超出一點，也不要縮成看不見內容。
        int maxPaneHeight = maxWindowHeight - headerHeight;
        int paneHeight = Math.min(contentHeight, Math.max(slotSize, maxPaneHeight));

        placeTitle(windowWidth);
        if (mode == Mode.USE) {
            placeButtons(windowWidth);
        }

        resize(windowWidth, headerHeight + paneHeight);

        paneX = 0;
        paneY = headerHeight;
        paneW = windowWidth;
        paneH = paneHeight;

        pane = new LootPane();
        add(pane);
        // 開啟時還原上次(關閉前)的捲動位置;超出目前內容範圍時 scrollTo 會自動夾回合法範圍。
        rebuild(rememberedScrollY());
    }

    /**
     * 兩種模式列的都是卷軸內的東西:TAKE 是全部(直接用卷軸的 live list),
     * USE 只留下「現在就能發動預設動作」的那些。卷軸每次收東西時就已排好序,
     * 所以這裡篩選後的順序天生就與 TAKE 視窗一致,不必再排一次。
     */
    private ArrayList<Item> collectItems() {
        if (mode == Mode.TAKE) {
            return scroll.getStored();
        }

        ArrayList<Item> usable = new ArrayList<>();
        Hero hero = Dungeon.hero;
        for (Item item : scroll.getStored()) {
            if (isUsable(hero, item)) {
                usable.add(item);
            }
        }
        return usable;
    }

    /**
     * 「有預設主動功能」的判準沿用原生 quickslot 的規則:defaultAction() != null
     * (見 QuickSlotButton.itemSelectable)。因此武器/護甲/戒指這類沒有主動行為的裝備不會出現。
     *
     * 再要求這個動作「此刻真的可用」:actions(hero) 是每個道具自己回報目前能做哪些事,
     * 神器沒裝備(或缺少對應天賦)時就不會列出自己的預設動作,於是自然被擋掉。
     * 用這個通用問法,就不必去 import 各衍生版本差異很大的神器/天賦類別。
     *
     * 另外手動排除三類:
     *  - 袋子:預設動作只是「打開袋子」,在這裡沒有意義。
     *  - 法杖:充能來自英雄身上的 buff,收在卷軸裡不會充能,不適合從卷軸內發動。
     *  - 投擲武器:射出去之後是掉在地上或插在敵人身上,回收走的是原生 pickup、
     *    一律進背包而不是回卷軸。
     * 後兩者仍然可以收進卷軸保管、也可以 Take 拿出來,只是不從卷軸內直接使用。
     */
    private boolean isUsable(Hero hero, Item item) {
        if (item == null || hero == null) {
            return false;
        }
        String action = item.defaultAction();
        if (action == null || item instanceof Bag) {
            return false;
        }
        if (ModItemKind.is(item, ModItemKind.WAND)
                || ModItemKind.is(item, ModItemKind.MISSILE_WEAPON)) {
            return false;
        }
        ArrayList<String> actions = item.actions(hero);
        return actions != null && actions.contains(action);
    }

    /**
     * USE 模式的 2x2 功能鍵,四個動作與原本的 WndOptions 選單完全相同:
     * Loot(全地圖收割)/ Put(把背包東西收進卷軸)/ Take(開啟 TAKE 模式)/ Dump(一次倒光)。
     * 一律先 hide() 再執行:Loot/Dump 會消耗英雄時間,Put/Take 則要另外開視窗。
     */
    private void placeButtons(int width) {
        final int stored = scroll.getStored().size();
        float half = width / 2f;
        float top = TITLE_HEIGHT;

        RedButton loot = new RedButton("Loot", 8) {
            @Override
            protected void onClick() {
                hide();
                scroll.doRead();
            }
        };
        loot.setSize(half, BTN_HEIGHT);
        loot.setPos(0, top);
        add(loot);

        RedButton put = new RedButton("Put", 8) {
            @Override
            protected void onClick() {
                hide();
                scroll.showPutSelector();
            }
        };
        put.setSize(half, BTN_HEIGHT);
        put.setPos(half, top);
        add(put);

        top += BTN_HEIGHT + BTN_MARGIN;

        RedButton take = new RedButton("Take (" + stored + ")", 8) {
            @Override
            protected void onClick() {
                hide();
                GameScene.show(new WndModLoot(scroll, Mode.TAKE));
            }
        };
        take.setSize(half, BTN_HEIGHT);
        take.setPos(0, top);
        take.enable(stored > 0);
        add(take);

        RedButton dump = new RedButton("Dump (" + stored + ")", 8) {
            @Override
            protected void onClick() {
                hide();
                scroll.doDump(Dungeon.hero);
            }
        };
        dump.setSize(half, BTN_HEIGHT);
        dump.setPos(half, top);
        dump.enable(stored > 0);
        add(dump);
    }

    /**
     * 每幀持續記錄目前的捲動高度(對齊 ModDepthSelector / ModBestiaryTab 等已驗證可用的視窗做法)。
     * 只在點擊當下才讀一次 scroll.y 並不可靠,改為逐幀鏡像下來,rebuild 時才有正確的還原值。
     */
    @Override
    public synchronized void update() {
        super.update();
        if (pane != null && pane.content() != null && pane.content().camera != null) {
            rememberScrollY(pane.content().camera.scroll.y);
        }
        // 保險:除了 offset() 之外,任何讓視窗換位置的途徑都能被接住(見 offset() 的說明)
        if (camera() != null && (camera().x != lastCamX || camera().y != lastCamY)) {
            lastCamX = camera().x;
            lastCamY = camera().y;
            relayoutPane();
        }
    }

    private void rememberScrollY(float y) {
        if (mode == Mode.USE) {
            lastScrollYUse = y;
        } else {
            lastScrollYTake = y;
        }
    }

    private float rememberedScrollY() {
        return mode == Mode.USE ? lastScrollYUse : lastScrollYTake;
    }

    /**
     * 點到某一格的物品。
     *
     * TAKE:收回/丟棄後就地重建清單,並還原先前的捲動位置(像其他 Mod 視窗那樣),可以連續點。
     * USE :對齊原生 WndQuickBag——先關窗再使用。消耗回合的動作(吃/讀/喝)要讓出畫面,
     *       需要瞄準的動作(法杖 ZAP、投擲 THROW)則是 execute 內部會呼叫 GameScene.selectCell,
     *       視窗還開著就點不到地圖。實際的使用流程交給 ModScrollOfLoot.useSingle()。
     */
    private void onSelect(Item item) {
        if (mode == Mode.TAKE) {
            scroll.takeSingle(Dungeon.hero, item);
            rebuild(rememberedScrollY());
            return;
        }

        Hero hero = Dungeon.hero;
        if (hero == null || !hero.isAlive() || !scroll.getStored().contains(item)) {
            ShatteredPixelDungeon.scene().addToFront(new WndInfoItem(item));
            return;
        }

        hide();
        scroll.useSingle(hero, item);
    }

    /**
     * 視窗被移動後,捲動面板必須重新 layout 一次。
     *
     * ScrollPane 的內容跑在自己的 Camera 上,螢幕座標是在 layout() 當下用視窗位置換算出來的;
     * 而 GameScene.show() 是在視窗建構完之後才套用「繼承自前一個視窗」的 offset
     * (桌面版把介面模式設成全螢幕、背包欄常駐時就會走到這條路)。不重新 layout 的話,
     * 視窗外框會移到新位置,可點擊的格子卻留在原地,看起來就是「點不到物品」。
     * 原生的 WndJournal / WndHero 也是用同一招處理。
     */
    @Override
    public void offset(int xOffset, int yOffset) {
        super.offset(xOffset, yOffset);
        relayoutPane();
    }

    private void relayoutPane() {
        if (pane != null) {
            pane.setRect(paneX, paneY, paneW, paneH);
        }
    }

    /**
     * 依目前 stored 內容刷新格子,補滿最後一列為空格子(對齊 WndBag 的滿格矩形外觀)。
     *
     * 【效能關鍵】不再 clear 後整批重建。存放上百件時,每次 Take 都重建整個面板
     * (為每一格 new 一個 InventorySlot,底下又各自 new 出 ColorBlock/ItemSprite/多個
     *  BitmapText/圖示),舊的一整批瞬間變成垃圾,單次點擊就製造數百個物件的配置與回收,
     * 造成明顯卡頓,且關閉後 GC 仍在追趕而持續頓。
     *
     * 改為「就地調整格子池」:沿用既有 Slot / InventorySlot 物件,只重新指派 item
     * (InventorySlot.item() 原生就支援換綁),並僅依總格數差額增減格子。
     * 這樣一次 Take 幾乎不產生新配置,消除 GC 壓力。
     */
    private void rebuild(float scrollY) {
        pane.reconcile(items);

        pane.setRect(paneX, paneY, paneW, paneH);
        pane.scrollTo(0, scrollY);
    }

    /** 幾乎照搬 WndBag.placeTitle:右上角顯示金幣(與能量),左側顯示標題與目前持有數量。 */
    private void placeTitle(int width) {
        float titleWidth;

        if (Dungeon.energy == 0) {
            ItemSprite gold = new ItemSprite(ItemSpriteSheet.GOLD, null);
            gold.x = width - gold.width();
            gold.y = (TITLE_HEIGHT - gold.height()) / 2f;
            PixelScene.align(gold);
            add(gold);

            BitmapText amt = new BitmapText(Integer.toString(Dungeon.gold), PixelScene.pixelFont);
            amt.hardlight(TITLE_COLOR);
            amt.measure();
            amt.x = width - gold.width() - amt.width() - 1;
            amt.y = (TITLE_HEIGHT - amt.baseLine()) / 2f - 1;
            PixelScene.align(amt);
            add(amt);

            titleWidth = amt.x;
        } else {
            Image gold = Icons.get(Icons.COIN_SML);
            gold.x = width - gold.width() - 0.5f;
            gold.y = 0;
            PixelScene.align(gold);
            add(gold);

            BitmapText amt = new BitmapText(Integer.toString(Dungeon.gold), PixelScene.pixelFont);
            amt.hardlight(TITLE_COLOR);
            amt.measure();
            amt.x = width - gold.width() - amt.width() - 2f;
            amt.y = 0;
            PixelScene.align(amt);
            add(amt);

            titleWidth = amt.x;

            Image energy = Icons.get(Icons.ENERGY_SML);
            energy.x = width - energy.width();
            energy.y = gold.height();
            PixelScene.align(energy);
            add(energy);

            amt = new BitmapText(Integer.toString(Dungeon.energy), PixelScene.pixelFont);
            amt.hardlight(0x44CCFF);
            amt.measure();
            amt.x = width - energy.width() - amt.width() - 1;
            amt.y = energy.y;
            PixelScene.align(amt);
            add(amt);

            titleWidth = Math.min(titleWidth, amt.x);
        }

        // USE 模式的數量已經寫在 Take/Dump 兩顆按鈕上,標題就不再重複
        String title = mode == Mode.USE
                ? scroll.name()
                : scroll.name() + " (" + scroll.getStored().size() + ")";
        RenderedTextBlock txtTitle = PixelScene.renderTextBlock(Messages.titleCase(title), 8);
        txtTitle.hardlight(TITLE_COLOR);
        txtTitle.maxWidth((int) titleWidth - 2);
        txtTitle.setPos(1, (TITLE_HEIGHT - txtTitle.height()) / 2f - 1);
        PixelScene.align(txtTitle);
        add(txtTitle);
    }

    /**
     * 可捲動的格子容器。結構對齊 ScrollingGridPane / ScrollingListPane:
     * 內容只是一堆 render-only 的 Slot,點擊由本 pane 統一手動命中測試。
     */
    private class LootPane extends ScrollPane {

        private final ArrayList<Slot> slots = new ArrayList<>();

        LootPane() {
            super(new Component());
            // 換裝支援「長按/右鍵 → 物品資訊」的控制器。
            // ScrollPane.createChildren() 已建立預設 PointerController,先拔掉再換上自訂版。
            remove(controller);
            controller.destroy();
            controller = new LootController();
            add(controller);
        }

        /**
         * 就地把格子池對齊到目前的 stored:先把格子總數(補滿整列)調到位,
         * 再把每一格重新指派為對應的 item(多出來的尾格指派為 null 空格)。
         * 沿用既有 Slot 物件,只有總數變動時才 new/destroy 少量格子,避免整批重建的 GC 風暴。
         */
        void reconcile(ArrayList<Item> stored) {
            int rows = Math.max(1, (int) Math.ceil(stored.size() / (float) NCOLS));
            int total = rows * NCOLS;

            while (slots.size() < total) {
                Slot s = new Slot();   // 在 LootPane 內建立,隱含的 LootPane.this 在作用域中
                content.add(s);
                slots.add(s);
            }
            while (slots.size() > total) {
                Slot s = slots.remove(slots.size() - 1);
                content.remove(s);
                s.destroy();
            }

            for (int i = 0; i < total; i++) {
                Item item = (i < stored.size()) ? stored.get(i) : null;
                slots.get(i).item(item);
            }
        }

        @Override
        public void onClick(float x, float y) {
            for (Slot s : slots) {
                if (s.onClick(x, y)) {
                    break;
                }
            }
        }

        /** 長按或右鍵:命中有物品的格子就顯示物品資訊。回傳是否有實際處理(用於決定震動與吃掉 click)。 */
        boolean onLongClick(float x, float y) {
            for (Slot s : slots) {
                if (s.inside(x, y)) {
                    if (s.item != null) {
                        ShatteredPixelDungeon.scene().addToFront(new WndInfoItem(s.item));
                        return true;
                    }
                    return false;
                }
            }
            return false;
        }

        /**
         * 在 ScrollPane 原本「乾淨點擊」派發之上,補上長按與右鍵支援。
         *
         * 長按仿照 Button 的實作模式:按下開始累計 pressTime,期間若拖曳超過
         * dragThreshold 就取消(視為捲動),累計到 Button.longClick 秒即觸發,
         * 觸發後設 longClicked 旗標吃掉放開時產生的 onClick,避免長按完又收取物品。
         * 右鍵(PC)則直接看 PointerEvent.button,不經過長按計時。
         */
        private class LootController extends PointerController {

            private boolean pressing = false;
            private boolean longClicked = false;
            private float pressTime = 0;
            private final PointF pressStart = new PointF();
            private final float pressDragThreshold = PixelScene.defaultZoom * 8;

            @Override
            protected void onPointerDown(PointerEvent event) {
                super.onPointerDown(event);
                pressing = true;
                longClicked = false;   // 每次按下都重置,避免上次長按後在面板外放開造成旗標殘留
                pressTime = 0;
                pressStart.set(event.current);
            }

            @Override
            protected void onPointerUp(PointerEvent event) {
                super.onPointerUp(event);
                pressing = false;
            }

            @Override
            protected void onDrag(PointerEvent event) {
                if (longClicked) return;   // 長按已觸發,忽略殘餘拖曳,避免資訊視窗底下的面板被捲動
                if (pressing && PointF.distance(event.current, pressStart) > pressDragThreshold) {
                    pressing = false;      // 位移過大 → 視為捲動手勢,取消長按計時
                }
                super.onDrag(event);
            }

            @Override
            public void update() {
                super.update();
                if (pressing && (pressTime += Game.elapsed) >= Button.longClick) {
                    pressing = false;
                    PointF p = content.camera.screenToCamera((int) pressStart.x, (int) pressStart.y);
                    if (LootPane.this.onLongClick(p.x, p.y)) {
                        longClicked = true;
                        if (SPDSettings.vibration()) {
                            Game.vibrate(50);
                        }
                    }
                }
            }

            @Override
            protected void onClick(PointerEvent event) {
                if (longClicked) {
                    longClicked = false;   // 這次 click 是長按的殘影,吃掉
                    return;
                }
                if (event.button == PointerEvent.RIGHT) {
                    PointF p = content.camera.screenToCamera((int) event.current.x, (int) event.current.y);
                    LootPane.this.onLongClick(p.x, p.y);
                } else {
                    super.onClick(event);  // 走原本流程 → ScrollPane.onClick → LootPane.onClick(收取物品)
                }
            }
        }

        @Override
        protected void layout() {
            int n = slots.size();
            int rows = (n == 0) ? 0 : (int) Math.ceil(n / (float) NCOLS);

            for (int i = 0; i < n; i++) {
                int col = i % NCOLS;
                int row = i / NCOLS;
                float sx = col * (slotSize + SLOT_MARGIN);
                float sy = row * (slotSize + SLOT_MARGIN);
                slots.get(i).setRect(sx, sy, slotSize, slotSize);
            }

            int contentHeight = (rows == 0) ? 0 : rows * slotSize + (rows - 1) * SLOT_MARGIN;
            content.setSize(width, contentHeight);

            super.layout();
        }

        /** 單一格子:用 InventorySlot 畫出 WndBag 等級的外觀,點擊命中後回呼視窗。 */
        private class Slot extends Component {

            Item item;
            private InventorySlot visual;

            Slot() {
                super();

                // 【關鍵一】不再把整個 InventorySlot 設成 active=false。
                // noosa 的 Group.update() 只會更新 exists && active 的子節點,
                // 一旦整格 inactive,底下 ItemSprite 的 update() 就不會被呼叫,
                // 而附魔閃爍(Glowing)正是在 ItemSprite.update() 裡逐幀推進的,
                // 所以圖示畫得出來(draw 只看 visible)卻永遠凍在無光的那一幀。
                //
                // 改用 ModDepthSelector.VisualRedButton 驗證過的「拔除 hotArea」手法:
                // 把 PointerArea 從場景圖中 remove 掉,輸入派發根本找不到它,
                // 觸控 100% 穿透給外層 ScrollPane 的 PointerController;
                // 同時 InventorySlot 保持完全 active,動畫照常更新,也不會觸發調暗。
                // (Button.update() 每幀做 hotArea.active = visible 也無所謂,
                //  因為不在場景圖裡的 PointerArea 再 active 也收不到事件。)
                visual = new InventorySlot(null) {
                    {
                        remove(hotArea);
                    }
                };
                add(visual);
            }

            /**
             * 重新指派這一格代表的物品(可傳 null 表示空格)。供格子池就地刷新使用,
             * 避免整批重建。若目標物件與現況相同就直接跳過,連 InventorySlot 內部
             * 的文字/圖示刷新都省掉(Take 只會讓被取走那格之後的物品往前遞補,
             * 前面沒動到的格子因此完全不必重畫)。
             */
            void item(Item item) {
                if (this.item == item) {
                    return;
                }
                this.item = item;
                visual.item(item);
            }

            @Override
            public synchronized void update() {
                super.update();
                // 【關鍵二/保險】萬一 visual 因任何原因處於 inactive
                // (例如空格子以外的狀態被舊邏輯關掉),被 Group.update() 跳過,
                // 就由必定 active 的外層 Slot 手動推進它的動畫。
                // 只對有物品的格子做:空格子的 sprite 會被 ItemSprite.update()
                // 強制設回 visible,不能去泵它,否則會冒出佔位圖。
                if (item != null && visual != null && visual.exists && !visual.active) {
                    visual.update();
                }
            }

            @Override
            protected void layout() {
                if (visual != null) {
                    visual.setRect(x, y, width, height);
                }
            }

            boolean onClick(float cx, float cy) {
                if (!inside(cx, cy)) {
                    return false;
                }
                // 空格子:吃掉點擊但不做事、也不關閉(本來就在 chrome 內)
                if (item != null) {
                    WndModLoot.this.onSelect(item);
                }
                return true;
            }
        }
    }
}
