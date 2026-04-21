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

        ArrayList<Integer> path = findPath(heroPos, targetPos);

        int bestPos = -1;
        int originalPos = hero.pos;

        for (Integer node : path) {
            hero.pos = node;
            if (hero.canAttack(target)) {
                bestPos = node;
            }
        }

        hero.pos = originalPos;
        return bestPos;
    }

    private static ArrayList<Integer> findPath(int startPos, int targetPos) {
        ArrayList<Integer> path = new ArrayList<>();
        int currentPos = startPos;
        path.add(currentPos);

        int steps = 32;

        while (steps > 0) {
            if (currentPos == targetPos) {
                break;
            }

            int bestNeighbor = getBestNeighbor(currentPos, targetPos);

            if (bestNeighbor == -1) {
                break;
            }

            path.add(bestNeighbor);
            currentPos = bestNeighbor;
            steps--;
        }

        return path;
    }

    private static int getBestNeighbor(int pos, int targetPos) {
        Level level = Dungeon.level;
        boolean[] passable = level.passable;
        int width = level.width();

        int[] offsets = { -1, 1, -width, width, -width - 1, -width + 1, width - 1, width + 1 };
        
        int bestNeighbor = -1;
        int minDist = Integer.MAX_VALUE;

        for (int i = 0; i < 8; i++) {
            int neighbor = offsets[i] + pos;
            
            if (neighbor >= 0 && neighbor < passable.length) {
                if (passable[neighbor]) {
                    
                    if (neighbor != targetPos) {
                        Char c = Actor.findChar(neighbor);
                        if (c != null) {
                            continue;
                        }
                    }

                    int dist = level.distance(neighbor, targetPos);
                    if (dist < minDist) {
                        minDist = dist;
                        bestNeighbor = neighbor;
                    }
                }
            }
        }

        return bestNeighbor;
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
