package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

import com.spd.mod.mechanics.ModFlash;

public class ModScrollOfDisplacement extends Scroll {

    public ModScrollOfDisplacement() {
        super();
        this.icon = ItemSpriteSheet.Icons.SCROLL_PASSAGE; // 0x35
        this.unique = true;
        this.usesTargeting = true;
    }

    @Override
    public String name() {
        return "Scroll of Displacement";
    }

    @Override
    public String info() {
        return desc();
    }

    @Override
    public void execute(Hero hero, String action) {
        if ("READ".equals(action)) {
            doRead();
        } else {
            super.execute(hero, action);
        }
    }

    public void doRead() {
        GameScene.selectCell(new TargetSelector());
    }

    @Override
    public String desc() {
        return "Select any character or item, then select a cell to displace them there.";
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
    public void reset() {
        this.image = new ScrollOfUpgrade().image;
        this.rune = "scroll_teleport";
    }

    @Override
    public void setKnown() {
    }

    public static class TargetSelector extends com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector.Listener implements com.watabou.utils.Callback {

        @Override
        public void call() {
            GameScene.selectCell(this);
        }

        @Override
        public void onSelect(Integer cell) {
            if (cell == null) return;

            if (!Dungeon.level.insideMap(cell)) {
                GLog.w("Invalid location.");
                return;
            }

            Char ch = com.shatteredpixel.shatteredpixeldungeon.actors.Actor.findChar(cell);
            if (ch != null) {
                ShatteredPixelDungeon.runOnRenderThread(new DestSelector(ch));
                return;
            }

            Heap heap = Dungeon.level.heaps.get(cell);
            if (heap != null) {
                ShatteredPixelDungeon.runOnRenderThread(new DestSelector(heap));
                return;
            }

            GLog.w("Nothing there.");
        }

        @Override
        public String prompt() {
            return "Select target to displace";
        }
    }

    public static class DestSelector extends com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector.Listener implements com.watabou.utils.Callback {

        private Char targetChar;
        private Heap targetHeap;

        public DestSelector(Char ch) {
            this.targetChar = ch;
            this.targetHeap = null;
        }

        public DestSelector(Heap heap) {
            this.targetHeap = heap;
            this.targetChar = null;
        }

        @Override
        public void call() {
            Dungeon.observe();
            GameScene.selectCell(this);
        }

        @Override
        public void onSelect(Integer cell) {
            if (cell == null) return;

            if (!Dungeon.level.insideMap(cell)) {
                GLog.w("Cannot travel there.");
                ShatteredPixelDungeon.runOnRenderThread(this);
                return;
            }

            if (targetChar != null) {
                ModFlash.teleport(targetChar, cell);
            } else if (targetHeap != null) {
                java.util.LinkedList<Item> itemsToMove = new java.util.LinkedList<>(targetHeap.items);
                targetHeap.destroy();
                for (Item item : itemsToMove) {
                    Dungeon.level.drop(item, cell);
                }
            }

            ShatteredPixelDungeon.runOnRenderThread(new TargetSelector());
        }

        @Override
        public String prompt() {
            return "Select destination cell";
        }
    }
}
