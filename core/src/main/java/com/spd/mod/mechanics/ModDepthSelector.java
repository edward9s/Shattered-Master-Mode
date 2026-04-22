package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndGame;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTitledMessage;
import com.watabou.noosa.Game;

import com.spd.mod.ModGame;

public class ModDepthSelector extends WndTitledMessage {

    public ModDepthSelector() {
        super(Icons.STAIRS.get(), "Switch Level", "Select a depth to travel to.");

        int y = this.height + 2;

        int maxDepth = Statistics.deepestFloor;
        if (maxDepth < ModGame.maxDepth()) {
            maxDepth = ModGame.maxDepth();
        }

        int xOffset = 0;
        int i = maxDepth;

        while (i > 0) {
            if (i % 5 == 0 && xOffset > 0) {
                y += 17;
                xOffset = 0;
            }

            DepthButton btn = new DepthButton(i, Integer.toString(i));

            if (i == Dungeon.depth) {
                btn.textColor(0x44ffff);
            }

            btn.setRect(xOffset, y, 23.0f, 16.0f);
            add(btn);

            xOffset += 24;
            i--;
        }

        int finalWidth = this.width;
        int padding = (xOffset == 0) ? 0 : 16;
        resize(finalWidth, y + padding);
    }

    private class DepthButton extends RedButton {
        private int depth;

        public DepthButton(int depth, String label) {
            super(label);
            this.depth = depth;
        }

        @Override
        protected void onClick() {
            InterlevelScene.returnDepth = this.depth;
            InterlevelScene.returnBranch = 0;
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
            GameScene.show(new ModDepthSelector());
        }
    }
}
