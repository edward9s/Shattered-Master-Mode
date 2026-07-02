package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.InventorySlot;
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

/**
 * 視覺上重刻 WndBag(含 InventorySlot 等級的格子精緻度),差別在於整個物品區可以「垂直捲動」。
 *
 * 互動規則(對齊使用者需求):
 *  - 點擊某個有物品的格子 → 透過 ModScrollOfLoot.takeSingle() 把它 collect 回背包,
 *    背包滿則 drop 到英雄腳下。動作後就地刷新清單,並保留捲動位置。
 *  - 長按(或 PC 上右鍵)有物品的格子 → 顯示該物品的資訊視窗(WndInfoItem),不收取。
 *  - 點擊「空格子」或視窗內任何位置 → 不關閉(因為都落在 chrome 範圍內,Window.blocker 不會觸發)。
 *  - 點擊視窗「外」 → 由 Window 內建的 blocker 關閉。
 *  => 因此可以連續點擊,直到自己點到外面為止。
 *
 * 點擊派發採用 ScrollPane 既有慣用法(同 ScrollingGridPane / ScrollingListPane):
 * 由 PointerController 統一接管指標輸入,只把「乾淨的點擊」轉成內容座標丟給 pane.onClick(),
 * 再手動命中測試到對應格子。InventorySlot 只負責「畫」,不靠自己的按鈕事件。
 */
public class WndModLoot extends Window {

    private static final int NCOLS      = 5;   // 對齊 WndBag 的 COLS_P / COLS_L
    private static final int SLOT_BASE  = 28;  // 對齊 WndBag 的 SLOT_WIDTH/HEIGHT
    private static final int SLOT_MARGIN = 1;
    private static final int TITLE_HEIGHT = 14;

    private final ModScrollOfLoot scroll;

    private LootPane pane;
    private int paneX, paneY, paneW, paneH;
    private int slotSize;

    public WndModLoot(ModScrollOfLoot scroll) {
        super();
        this.scroll = scroll;

        slotSize = SLOT_BASE;
        int windowWidth = slotSize * NCOLS + SLOT_MARGIN * (NCOLS - 1);

        // 直向螢幕太窄時,跟 WndBag 一樣把格子縮一點以塞進寬度(垂直方向交給捲動,不縮)
        if (!PixelScene.landscape()) {
            while (slotSize >= 26 && (windowWidth + chrome.marginHor()) > PixelScene.uiCamera.width) {
                slotSize--;
                windowWidth -= NCOLS;
            }
        }

        // 內容高度(含補滿最後一列的空格子),以及可視區高度上限
        int count = scroll.getStored().size();
        int rows = Math.max(1, (int) Math.ceil(count / (float) NCOLS));
        int contentHeight = rows * slotSize + (rows - 1) * SLOT_MARGIN;

        int maxPaneHeight = (int) (PixelScene.uiCamera.height * 0.85f) - TITLE_HEIGHT;
        int paneHeight = Math.min(contentHeight, Math.max(slotSize, maxPaneHeight));

        placeTitle(windowWidth);

        resize(windowWidth, TITLE_HEIGHT + paneHeight);

        paneX = 0;
        paneY = TITLE_HEIGHT;
        paneW = windowWidth;
        paneH = paneHeight;

        pane = new LootPane();
        add(pane);
        rebuild(0f);
    }

    /** 收回/丟棄一件物品後重建清單,並還原先前的捲動位置(像其他 Mod 視窗那樣)。 */
    private void onSelect(Item item) {
        float scrollY = (pane != null) ? pane.content().camera.scroll.y : 0f;

        scroll.takeSingle(Dungeon.hero, item);

        rebuild(scrollY);
    }

    /** 依目前 stored 內容重建格子,補滿最後一列為空格子(對齊 WndBag 的滿格矩形外觀)。 */
    private void rebuild(float scrollY) {
        pane.clear();

        ArrayList<Item> stored = scroll.getStored();
        int rows = Math.max(1, (int) Math.ceil(stored.size() / (float) NCOLS));
        int total = rows * NCOLS;

        for (int i = 0; i < total; i++) {
            Item item = (i < stored.size()) ? stored.get(i) : null;
            pane.addSlot(item);
        }

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

        String title = scroll.name() + " (" + scroll.getStored().size() + ")";
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

        void addSlot(Item item) {
            Slot s = new Slot(item);   // 在 LootPane 內建立,隱含的 LootPane.this 在作用域中
            content.add(s);
            slots.add(s);
        }

        public synchronized void clear() {
            content.clear();
            slots.clear();
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

            final Item item;
            private InventorySlot visual;

            Slot(Item item) {
                super();                 // 注意:super() 會先觸發 createChildren(),此時 item 尚未指派
                this.item = item;

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
                visual = new InventorySlot(item) {
                    {
                        remove(hotArea);
                    }
                };
                add(visual);
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
