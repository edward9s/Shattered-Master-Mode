package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfRegrowth;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.plants.BlandfruitBush;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import java.util.Iterator;

public class ModLoot {

    public static class Trample {
        public static boolean canTrample(int tile) {
            // 使用 Terrain 常數替換 15 與 30
            return tile == Terrain.HIGH_GRASS || tile == Terrain.FURROWED_GRASS;
        }

        public static boolean canRegrow() {
            return Dungeon.hero.heroClass == HeroClass.HUNTRESS;
        }

        public static void execute() {
            System.out.println("SPD_MOD: === trampleGrass (Delegate) ===");
            Level level = Dungeon.level;
            if (level == null) return;

            if (level.map != null) {
                for (int i = 0; i < level.map.length; i++) {
                    if (canTrample(level.map[i])) {
                        level.pressCell(i);
                        if (canRegrow()) {
                            // 強制設定為經過踩踏的草叢常數
                            Level.set(i, Terrain.FURROWED_GRASS);
                            GameScene.updateMap(i);
                        }
                    }
                }
            }

            if (level.plants != null) {
                Iterator<Plant> it = level.plants.valueList().iterator();
                while (it.hasNext()) {
                    Plant p = it.next();
                    if (p instanceof WandOfRegrowth.Dewcatcher || 
                        p instanceof WandOfRegrowth.Seedpod || 
                        p instanceof BlandfruitBush) {
                        level.pressCell(p.pos);
                    }
                }
            }
        }
    }
}
