package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndGame;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTitledMessage;
import com.watabou.noosa.Game;
import com.watabou.noosa.ui.Component;

import com.spd.mod.ModGame;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.ArrayList;

public class ModDepthSelector extends WndTitledMessage {

    private static int selectedBranch = 0;
    private static TreeMap<Integer, TreeSet<Integer>> safeFloors;
    
    // 跨視窗記錄滑動位置
    private static float savedScrollY = 0f;

    private DepthSelectorPane scrollPane;

    public ModDepthSelector() {
        super(Icons.STAIRS.get(), "Teleport", null);
        
        // 初始安全尺寸
        resize(130, 160);
        
        buildSafeRegistry();

        if (!safeFloors.containsKey(selectedBranch)) {
            selectedBranch = 0;
        }

        RenderedTextBlock tip = PixelScene.renderTextBlock("Pick a Branch & Depth", 6);
        add(tip);
        tip.setPos((this.width - tip.width()) / 2f, 24);

        float currentY = tip.bottom() + 6;

        // --- 預先計算內容高度 ---
        float btnX = 0;
        float btnY = 0;
        for (int branchId : safeFloors.keySet()) {
            if (btnX + 26 > this.width) { btnX = 0; btnY += 18; }
            btnX += 28;
        }
        btnY += 22; 
        btnX = 0; 
        int count = 0;
        for (int depth : safeFloors.get(selectedBranch)) {
            btnX += 24; count++;
            if (count % 5 == 0) { btnY += 18; btnX = 0; }
        }
        float contentH = btnY + (btnX == 0 ? 0 : 18);
        
        // 【修正 1】：使用標準 UI 偵測，動態給定橫向/直向的最大視窗高度
        float maxViewHeight = PixelScene.landscape() ? 70f : 140f; 
        float viewHeight = Math.min(contentH, maxViewHeight);

        scrollPane = new DepthSelectorPane();
        add(scrollPane); 
        scrollPane.setRect(0, currentY, this.width, viewHeight); 

        // 裁切最終視窗
        resize(130, (int)(currentY + viewHeight + 6));
    }

    // 【修正 2】：當視窗置中位移時，強制抵消內部元件的雙重偏移
    @Override
    public void offset(int xOffset, int yOffset) {
        super.offset(xOffset, yOffset);
        if (scrollPane != null) {
            scrollPane.reLayout();
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

    private class DepthSelectorPane extends ScrollPane {
        private ArrayList<BranchTab> branchTabs = new ArrayList<>();
        private ArrayList<DepthButton> depthButtons = new ArrayList<>();

        public DepthSelectorPane() {
            super(new Component());

            for (int branchId : safeFloors.keySet()) {
                BranchTab btn = new BranchTab(branchId);
                if (branchId == selectedBranch) btn.textColor(0xffff44);
                branchTabs.add(btn);
                content.add(btn); 
            }

            for (int depth : safeFloors.get(selectedBranch)) {
                DepthButton btn = new DepthButton(selectedBranch, depth, Integer.toString(depth));
                if (depth == Dungeon.depth && selectedBranch == Dungeon.branch) btn.textColor(0x44ffff);
                depthButtons.add(btn);
                content.add(btn);
            }
        }

        @Override
        public void update() {
            super.update();
            if (content != null && content.camera != null) {
                savedScrollY = content.camera.scroll.y;
            }
        }

        // 開放給外層呼叫的排版更新方法
        public void reLayout() {
            layout();
        }

        @Override
        protected void layout() {
            if (branchTabs == null || depthButtons == null) return;

            // 【核心重置】：確保無論視窗被推移到哪裡，按鈕永遠從局部的 0, 0 開始排版
            float cx = 0;
            float cy = 0; 

            for (BranchTab btn : branchTabs) {
                if (cx + 26 > ModDepthSelector.this.width) {
                    cx = 0;
                    cy += 18;
                }
                btn.setRect(cx, cy, 26, 16);
                cx += 28;
            }

            cy += 22; 
            cx = 0;
            int count = 0;

            for (DepthButton btn : depthButtons) {
                btn.setRect(cx, cy, 23, 16);
                cx += 24;
                count++;
                if (count % 5 == 0) {
                    cy += 18;
                    cx = 0;
                }
            }

            float finalY = cy + (cx == 0 ? 0 : 18);
            content.setSize(ModDepthSelector.this.width, finalY);

            super.layout();
            scrollTo(0, savedScrollY);
        }
    }

    private class BranchTab extends RedButton {
        private int branchId;
        public BranchTab(int branchId) {
            super("B" + branchId);
            this.branchId = branchId;
        }
        @Override
        protected void onClick() {
            selectedBranch = this.branchId;
            savedScrollY = 0f; // 切換分頁時將滾動條歸零，體驗較佳
            refresh();
        }
    }

    private class DepthButton extends RedButton {
        private int branch;
        private int depth;
        public DepthButton(int branch, int depth, String label) {
            super(label);
            this.branch = branch;
            this.depth = depth;
        }
        @Override
        protected void onClick() {
            InterlevelScene.returnDepth = this.depth;
            InterlevelScene.returnBranch = this.branch;
            InterlevelScene.returnPos = -1;
            InterlevelScene.mode = InterlevelScene.Mode.RETURN;
            Game.switchScene(InterlevelScene.class);
        }
    }

    public static class OpenBtn extends RedButton {
        public OpenBtn() {
            super("Levels");
            textColor(0xffff44);
        }
        @Override
        protected void onClick() {
            if (WndGame.instance != null) {
                WndGame.instance.hide();
            }
            selectedBranch = Dungeon.branch;
            GameScene.show(new ModDepthSelector());
        }
    }
}
