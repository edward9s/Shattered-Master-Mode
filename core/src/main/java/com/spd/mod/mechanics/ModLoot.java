package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.PinCushion;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Dewdrop;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.Waterskin;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfRegrowth;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.plants.BlandfruitBush;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

import java.util.Iterator;

public class ModLoot {

    // --- 對外呼叫介面 ---
    public static void trampleGrass() {
        Trample.execute();
    }

    /** @return 真正撿進背包(會播放 ITEM 撿取音效)的件數。 */
    public static int collectHeaps() {
        return Collect.execute();
    }

    /** @return 真正撿進背包(會播放 ITEM 撿取音效)的件數。 */
    public static int grabItems() {
        return Grab.execute();
    }

    // --- 內部實作：拔箭 ---
    public static class Grab {
        /** @return 成功拔進背包的件數(每次成功都會播放 ITEM 音效)。 */
        public static int execute() {
            Level level = Dungeon.level;
            Hero hero = Dungeon.hero;
            if (level == null || hero == null) return 0;

            int pickedUp = 0;
            float start = hero.cooldown();

            Iterator<Mob> it = level.mobs.iterator();
            while (it.hasNext()) {
                Mob mob = it.next();
                while (true) {
                    PinCushion pc = (PinCushion) mob.buff(PinCushion.class);
                    if (pc == null) break;

                    Item item = pc.grabOne();
                    if (item == null) break;

                    boolean picked = item.doPickUp(hero, mob.pos);
                    if (picked) {
                        GLog.i("Grabbed: " + item.name());
                        pickedUp++;
                    } else {
                        level.drop(item, mob.pos);
                        break;
                    }
                }
            }

            float end = hero.cooldown();
            hero.spendConstant(start - end);
            return pickedUp;
        }
    }

    // --- 內部實作：撿取 ---
    public static class Collect {
        /**
         * @return 真正撿進背包(會播放 ITEM 音效)的件數。
         *         露珠走的是 Dewdrop.doPickUp:播 DEWDROP 而非 ITEM、且不進背包,故不計入,
         *         讓「只撿到露珠 + 真道具溢出到卷軸」時,卷軸仍能補一次吸入音效而不會重疊。
         */
        public static int execute() {
            Level level = Dungeon.level;
            Hero hero = Dungeon.hero;
            if (level == null || hero == null) return 0;

            int pickedUp = 0;
            float start = hero.cooldown();

            if (level.heaps != null) {
                for (Heap heap : level.heaps.valueList()) {
                    if (heap == null || heap.pos == hero.pos) continue;
                    if (!isCollectable(heap.type)) continue;

                    while (!heap.isEmpty()) {
                        Item item = heap.peek();
                        if (item == null) break;

                        if (item instanceof Dewdrop) {
                            if (hero.HP >= hero.HT) {
                                Waterskin skin = (Waterskin) hero.belongings.getItem(Waterskin.class);
                                if (skin == null || skin.isFull()) {
                                    break; // 水袋已滿且血滿，跳過這個 Heap
                                }
                            }
                            boolean picked = ((Dewdrop) item).doPickUp(hero);
                            Item popped = heap.pickUp();
                            if (picked) {
                                GLog.i("Collected: " + popped.name());
                                // 露珠播 DEWDROP 而非 ITEM、也不算「進背包」→ 不計入。
                            } else {
                                level.drop(popped, hero.pos);
                            }
                        } else {
                            boolean picked = item.doPickUp(hero);
                            Item popped = heap.pickUp();
                            if (picked) {
                                GLog.i("Collected: " + popped.name());
                                pickedUp++;
                            } else {
                                level.drop(popped, hero.pos);
                            }
                        }
                    }
                }
            }

            float end = hero.cooldown();
            hero.spendConstant(start - end);
            return pickedUp;
        }

        public static boolean isCollectable(Heap.Type type) {
            return type == Heap.Type.HEAP ||
                   type == Heap.Type.CHEST ||
                   type == Heap.Type.REMAINS ||
                   type == Heap.Type.SKELETON;
        }
    }

    // --- 內部實作：除草 ---
    public static class Trample {
        public static boolean canTrample(int tile) {
            return tile == Terrain.HIGH_GRASS || tile == Terrain.FURROWED_GRASS;
        }

        public static boolean canRegrow() {
            return Dungeon.hero.heroClass == HeroClass.HUNTRESS;
        }

        public static void execute() {
            Level level = Dungeon.level;
            if (level == null) {
                return;
            }

            if (level.map != null) {
                for (int i = 0; i < level.map.length; i++) {
                    if (canTrample(level.map[i])) {
                        level.pressCell(i);
                        if (canRegrow()) {
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
