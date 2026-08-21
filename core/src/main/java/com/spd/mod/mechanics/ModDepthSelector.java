package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTitledMessage;
import com.watabou.noosa.Game;

import com.spd.mod.ModGame;
import java.util.TreeMap;
import java.util.TreeSet;

public class ModDepthSelector extends WndTitledMessage {

    private static int selectedBranch = 0;
    private static TreeMap<Integer, TreeSet<Integer>> safeFloors;
    
    private static float savedScrollY = 0f;
    private ScrollingGridPane grid;

    // 畫面上下兩端是遊戲介面：上方狀態列、下方工具列（大介面模式還有背包欄）。
    // Window 是垂直置中的，所以這個總量會被上下平分：留 100 代表上下各 50，兩端的
    // 介面按鈕都不會被蓋到。數字對齊 WndModLoot / 原生 WndQuickBag。
    private static final int UI_RESERVE_VER = 100;
    // 極小畫面（橫向只有 160）時至少留得下標題與一列按鈕。
    private static final int MIN_HEIGHT = 48;
    private static final int WIDTH = 130;
    // 格子區在視窗內的上下留白：上面讓開 IconTitle，下面留一點邊。
    private static final float GRID_TOP = 20f;
    private static final float GRID_BOTTOM = 10f;

    public ModDepthSelector() {
        super(Icons.STAIRS.get(), "Teleport", null);
        
        resize(WIDTH, maxWindowHeight());
        buildSafeRegistry();

        if (!safeFloors.containsKey(selectedBranch)) {
            selectedBranch = 0;
        }

        grid = new ScrollingGridPane();
        add(grid);

        grid.addHeader("Pick a Branch");
        for (int branchId : safeFloors.keySet()) {
            final int fBranchId = branchId;
            
            // 使用我們特製的「純視覺」按鈕
            VisualRedButton btn = new VisualRedButton("B" + branchId);
            if (branchId == selectedBranch) btn.textColor(0xffff44);
            
            grid.addItem(new ButtonGridItem(btn, 28f, 18f, () -> {
                selectedBranch = fBranchId;
                savedScrollY = 0f;
                refresh();
            }));
        }

        grid.addHeader("Pick a Depth");
        for (int depth : safeFloors.get(selectedBranch)) {
            final int fDepth = depth;
            
            VisualRedButton btn = new VisualRedButton(Integer.toString(depth));
            
            // 當前所在樓層保留青藍色文字
            if (depth == Dungeon.depth && selectedBranch == Dungeon.branch) {
                btn.textColor(0x44ffff); 
            } 
            
            // 5 的倍數樓層更改底色 (深色)
            if (depth % 5 == 0) {
                btn.tint(0x000000, 0.8f); 
            }
            
            grid.addItem(new ButtonGridItem(btn, 24f, 18f, () -> {
                InterlevelScene.returnDepth = fDepth;
                InterlevelScene.returnBranch = selectedBranch;
                InterlevelScene.returnPos = -1;
                InterlevelScene.mode = InterlevelScene.Mode.RETURN;
                Game.switchScene(InterlevelScene.class);
            }));
        }

        layoutGrid();
        fitHeightToContent();
        grid.scrollTo(0f, savedScrollY);
    }

    @Override
    public void update() {
        super.update();
        if (grid != null && grid.content() != null && grid.content().camera != null) {
            savedScrollY = grid.content().camera.scroll.y;
        }
    }

    @Override
    public void offset(int xOffset, int yOffset) {
        super.offset(xOffset, yOffset);
        if (grid != null) {
            layoutGrid();
        }
    }

    private void layoutGrid() {
        grid.setRect(0, GRID_TOP, this.width, this.height - GRID_TOP - GRID_BOTTOM);
    }

    /**
     * 高度上限與 WndModLoot 相同：整個視窗（含外框）不超過 uiCamera.height - UI_RESERVE_VER，
     * 不往上下擴張去蓋住狀態列/工具列。
     */
    private int maxWindowHeight() {
        int maxHeight = PixelScene.uiCamera.height - UI_RESERVE_VER - chrome.marginVer();
        return Math.max(MIN_HEIGHT, maxHeight);
    }

    /**
     * 動態高度：格子排完後量內容實際多高，視窗就收到剛好包住內容；
     * 內容比上限長（分支多／樓層多）才停在上限，看不完的部分交給捲動。
     */
    private void fitHeightToContent() {
        int wanted = (int) Math.ceil(GRID_TOP + grid.content().height() + GRID_BOTTOM);
        int height = Math.max(MIN_HEIGHT, Math.min(maxWindowHeight(), wanted));
        if (height != this.height) {
            resize(WIDTH, height);
            layoutGrid();
        }
    }

    private void buildSafeRegistry() {
        safeFloors = new TreeMap<>();
        safeFloors.put(0, new TreeSet<Integer>());
        safeFloors.put(1, new TreeSet<Integer>());

        for (int val : Dungeon.generatedLevels) {
            int b = val / 1000;
            int d = val % 1000;
            if (!safeFloors.containsKey(b)) safeFloors.put(b, new TreeSet<Integer>());
            safeFloors.get(b).add(d);
        }

        if (!safeFloors.containsKey(Dungeon.branch)) {
            safeFloors.put(Dungeon.branch, new TreeSet<Integer>());
        }
        safeFloors.get(Dungeon.branch).add(Dungeon.depth);

        for (TreeSet<Integer> depths : safeFloors.values()) {
            for (int d = 1; d <= ModGame.maxDepth(); d++) {
                depths.add(d);
            }
        }
    }

    private void refresh() {
        hide();
        GameScene.show(new ModDepthSelector());
    }

    // ==============================================================
    // 【終極殺招】：拔除觸控攔截器的特製按鈕
    // ==============================================================
    private class VisualRedButton extends RedButton {
        public VisualRedButton(String label) {
            super(label);
            // 由於 Button.java 中宣告了 protected PointerArea hotArea;
            // 我們可以直接把它從畫布物件群中 remove 掉。
            // 失去 hotArea 的按鈕就只剩貼圖與文字，觸控事件將 100% 穿透！
            remove(hotArea);
        }
        
        public void hardlight(int colorCode) {
            if (bg != null) {
                bg.hardlight(colorCode); 
            }
        }
        
        // 加入 strength 參數以控制疊加濃度 (0.0f ~ 1.0f)
        public void tint(int colorCode, float strength) {
            if (bg != null) {
                bg.tint(colorCode, strength); 
            }
        }
    }

    // ==============================================================
    // 【完美收尾】：接管邏輯的底層 GridItem
    // ==============================================================
    private class ButtonGridItem extends ScrollingGridPane.GridItem {
        private VisualRedButton btn;
        private Runnable action;

        public ButtonGridItem(VisualRedButton btn, float w, float h, Runnable action) {
            super(Icons.STAIRS.get());
            this.icon.alpha(0f); 
            
            this.width = w;
            this.height = h;
            
            this.btn = btn;
            this.action = action;
            add(this.btn);
        }

        @Override
        protected void layout() {
            super.layout();
            this.btn.setRect(this.x + 1f, this.y + 1f, this.width - 2f, this.height - 2f);
        }

        @Override
        public boolean onClick(float x, float y) {
            // 觸控成功穿透按鈕，由 ScrollingGridPane 的網格來接管判定與點擊
            if (!inside(x, y)) {
                return false;
            }
            if (action != null) {
                action.run();
            }
            return true;
        }
    }

    public static class OpenBtn extends RedButton {
        public OpenBtn() {
            super("Levels");
            textColor(0xffff44);
        }
        @Override
        protected void onClick() {
            if (parent instanceof Window) {
                ((Window) parent).hide();
            }
            selectedBranch = Dungeon.branch;
            GameScene.show(new ModDepthSelector());
        }
    }
}
