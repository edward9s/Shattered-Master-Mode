package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndGame;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTitledMessage;
import com.watabou.noosa.Game;

import com.spd.mod.ModGame;
import java.util.TreeMap;
import java.util.TreeSet;

public class ModDepthSelector extends WndTitledMessage {

    private static int selectedBranch = 0;
    private static TreeMap<Integer, TreeSet<Integer>> safeFloors;

    public ModDepthSelector() {
        super(Icons.STAIRS.get(), "Teleport", null);

        resize(120, 10);

        buildSafeRegistry();

        if (!safeFloors.containsKey(selectedBranch)) {
            selectedBranch = 0;
        }

        // 將 Y 偏移量從 +2 增加至 +8，拉開說明文字與標題間的距離
        RenderedTextBlock tip = PixelScene.renderTextBlock("Pick a Branch (Top) and a Depth (Grid)", 6);
        tip.setPos(0, this.height + 8); 
        add(tip);

        // 按鈕起始 Y 座標會自動隨文字底部 (bottom) 下移，維持比例
        int y = (int)(tip.bottom() + 6);
        int xOffset = 0;

        for (int branchId : safeFloors.keySet()) {
            if (xOffset + 26 > 120) {
                xOffset = 0;
                y += 18;
            }

            BranchTab btn = new BranchTab(branchId);
            btn.setRect(xOffset, y, 26, 16);
            if (branchId == selectedBranch) {
                btn.textColor(0xffff44);
            }
            add(btn);
            
            xOffset += 28;
        }

        y += 18; 
        y += 4; 

        xOffset = 0;
        int count = 0;
        for (int depth : safeFloors.get(selectedBranch)) {
            DepthButton btn = new DepthButton(selectedBranch, depth, Integer.toString(depth));
            
            if (depth == Dungeon.depth && selectedBranch == Dungeon.branch) {
                btn.textColor(0x44ffff);
            }
            
            btn.setRect(xOffset, y, 23, 16);
            add(btn);

            xOffset += 24;
            count++;
            if (count % 5 == 0) {
                y += 18;
                xOffset = 0;
            }
        }

        resize(120, y + (xOffset == 0 ? 0 : 18));
    }

    private void buildSafeRegistry() {
        safeFloors = new TreeMap<>();

        for (int b = 0; b <= 1; b++) {
            TreeSet<Integer> depths = new TreeSet<>();
            for (int d = 1; d <= ModGame.maxDepth(); d++) {
                depths.add(d);
            }
            safeFloors.put(b, depths);
        }

        for (int val : Dungeon.generatedLevels) {
            int b = val / 1000;
            int d = val % 1000;
            if (!safeFloors.containsKey(b)) {
                safeFloors.put(b, new TreeSet<Integer>());
            }
            safeFloors.get(b).add(d);
        }

        if (!safeFloors.get(Dungeon.branch).contains(Dungeon.depth)) {
            safeFloors.get(Dungeon.branch).add(Dungeon.depth);
        }
    }

    private void refresh() {
        hide();
        GameScene.show(new ModDepthSelector());
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
