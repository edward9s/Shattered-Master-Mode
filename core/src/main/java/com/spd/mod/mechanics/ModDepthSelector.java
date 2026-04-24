package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
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
        super(Icons.STAIRS.get(), "Teleport", "Select Branch (Top) or Depth (Grid)");

        buildSafeRegistry();

        if (!safeFloors.containsKey(selectedBranch)) {
            selectedBranch = 0;
        }

        int y = this.height + 2;
        int xOffset = 0;

        for (int branchId : safeFloors.keySet()) {
            if (xOffset + 24 > 120) {
                xOffset = 0;
                y += 17;
            }

            BranchTab btn = new BranchTab(branchId);
            btn.setRect(xOffset, y, 24, 16);
            if (branchId == selectedBranch) {
                btn.textColor(0xffff44);
            }
            add(btn);
            
            xOffset += 26;
        }

        y += 17; 
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
                y += 17;
                xOffset = 0;
            }
        }

        resize(120, y + (xOffset == 0 ? 0 : 17));
    }

    private void buildSafeRegistry() {
        safeFloors = new TreeMap<>();

        // 預設 B0-B3
        for (int b = 0; b <= 3; b++) {
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
