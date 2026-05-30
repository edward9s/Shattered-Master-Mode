package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.Wound;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

import java.util.ArrayList;

public class ModAssassin {

    public static void cast(Hero hero) {
        GameScene.selectCell(new Selector(hero));
    }

    public static void perform(Hero hero, Char target) {
        int bestPos = findBestPos(hero, target);

        if (bestPos == -1) {
            GLog.w("No valid attack position", new Object[0]);
            return;
        }

        ModFlash.perform(hero, bestPos);

        int originalInvisible = hero.invisible;
        hero.invisible = 1;

        Wound.hit(target);
        hero.attack(target);

        CharSprite sprite = hero.sprite;
        int targetPos = target.pos;
        if (sprite != null) {
            sprite.attack(targetPos);
        }

        hero.invisible = originalInvisible;
        hero.spendToWhole();
    }

    private static int findBestPos(Hero hero, Char target) {
        int targetPos = target.pos;
        int heroPos = hero.pos;

        // 使用 BFS 取得自然繞路路徑 (若被封死則取半截)
        ArrayList<Integer> path = findSmartPath(targetPos, heroPos);

        int bestPos = -1;
        int originalPos = hero.pos;
        Level level = Dungeon.level;

        for (Integer node : path) {
            Char occupant = Actor.findChar(node);
            boolean isFree = (occupant == null || occupant == hero);

            if (level.passable[node] && isFree) {
                hero.pos = node;
                if (hero.canAttack(target)) {
                    bestPos = node;
                } else {
                    break;
                }
            } else {
                break;
            }
        }

        hero.pos = originalPos;
        return bestPos;
    }

    // 結合 BFS 與暫存最近點的尋路演算法
    private static ArrayList<Integer> findSmartPath(int startPos, int targetPos) {
        Level level = Dungeon.level;
        boolean[] passable = level.passable;
        int w = level.width();
        int[] offsets = { -1, 1, -w, w, -w - 1, -w + 1, w - 1, w + 1 };

        int[] cameFrom = new int[level.length()];
        for (int i = 0; i < cameFrom.length; i++) {
            cameFrom[i] = -1;
        }

        ArrayList<Integer> queue = new ArrayList<>();
        queue.add(startPos);
        cameFrom[startPos] = startPos;

        int closestPos = startPos;
        int minDistance = level.distance(startPos, targetPos);
        
        // 防呆機制：限制最大探索步數，防止極度開闊地形造成效能問題
        int maxExplore = 512; 
        int head = 0;

        while (head < queue.size() && maxExplore > 0) {
            int current = queue.get(head++);
            
            if (current == targetPos) {
                closestPos = current;
                break; // 順利找到目標，提早結束
            }

            for (int offset : offsets) {
                int neighbor = current + offset;
                
                if (neighbor >= 0 && neighbor < passable.length && passable[neighbor]) {
                    if (cameFrom[neighbor] == -1) { // 尚未走過
                        cameFrom[neighbor] = current;
                        queue.add(neighbor);
                        
                        // 記錄探索過程中，離英雄幾何距離最近的格子
                        int dist = level.distance(neighbor, targetPos);
                        if (dist < minDistance) {
                            minDistance = dist;
                            closestPos = neighbor;
                        }
                    }
                }
            }
            maxExplore--;
        }

        // 從找到的最近點 (或目標點) 往回推導路徑
        ArrayList<Integer> path = new ArrayList<>();
        int curr = closestPos;
        while (curr != startPos && cameFrom[curr] != -1) {
            path.add(0, curr); // 往前插入，確保最後陣列方向是 敵人 -> 英雄
            curr = cameFrom[curr];
        }

        return path;
    }

    public static class Selector extends CellSelector.Listener {
        private Hero hero;

        public Selector(Hero hero) {
            this.hero = hero;
        }

        @Override
        public void onSelect(Integer pos) {
            if (pos == null) {
                return;
            }

            int cell = pos;
            Level level = Dungeon.level;

            if (!level.insideMap(cell)) {
                GLog.w("Cannot travel there.", new Object[0]);
                return;
            }

            Char target = Actor.findChar(cell);

            if (target == null) {
                ModFlash.perform(this.hero, cell);
            } else {
                ModAssassin.perform(this.hero, target);
            }
        }

        @Override
        public String prompt() {
            return "Select target or cell";
        }
    }
}
