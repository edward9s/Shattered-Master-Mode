package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.InventorySlot;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Image;
import com.watabou.noosa.ui.Component;

import java.util.ArrayList;

/**
 * 視覺上重刻 WndBag(含 InventorySlot 等級的格子精緻度),差別在於整個物品區可以「垂直捲動」。
 *
 * 互動規則(對齊使用者需求):
 *  - 點擊某個有物品的格子 → 透過 ModScrollOfLoot.releaseSingle() 把它 collect 回背包,
 *    背包滿則 drop 到英雄腳下。動作後就地刷新清單,並保留捲動位置。
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

        scroll.releaseSingle(Dungeon.hero, item);

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
                visual = new InventorySlot(item);
                // 關鍵:停用格子自身的指標熱區(InventorySlot 是個 Button)。
                // 直接設 active=false(而非 enable(false))→ 關掉輸入但不觸發調暗邏輯,
                // 讓點擊/拖曳全部落回外層 ScrollPane 的 PointerController。繪製只看 visible,仍維持全亮。
                visual.active = false;
                add(visual);
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
