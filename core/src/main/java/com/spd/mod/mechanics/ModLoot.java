package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.PinCushion;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Dewdrop;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfRegrowth;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.plants.BlandfruitBush;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

import java.util.Iterator;

public class ModLoot {

    public static class Result {
        private int pickedIntoBags;
        private int absorbed;

        public int pickedIntoBags() {
            return pickedIntoBags;
        }

        public int absorbed() {
            return absorbed;
        }

        public void add(Result other) {
            if (other != null) {
                pickedIntoBags += other.pickedIntoBags;
                absorbed += other.absorbed;
            }
        }
    }

    // --- 對外呼叫介面 ---
    public static void trampleGrass() {
        Trample.execute();
    }

    /** @return 真正撿進背包(會播放 ITEM 撿取音效)的件數。 */
    public static int collectHeaps() {
        return Collect.execute();
    }

    /**
     * 使用指定 Loot storage 收納背包放不下的物品。
     * @return 本輪真正進背包與被 storage 吸收的件數。
     */
    public static Result collectHeaps(ModLootStorage storage) {
        return Collect.execute(storage);
    }

    /** @return 真正撿進背包(會播放 ITEM 撿取音效)的件數。 */
    public static int grabItems() {
        return Grab.execute();
    }

    /**
     * 使用指定 Loot storage 收納背包放不下的投射物。
     * @return 本輪真正進背包與被 storage 吸收的件數。
     */
    public static Result grabItems(ModLootStorage storage) {
        return Grab.execute(storage);
    }

    // --- 內部實作：拔箭 ---
    public static class Grab {
        /** @return 成功拔進背包的件數(每次成功都會播放 ITEM 音效)。 */
        public static int execute() {
            return execute(null).pickedIntoBags();
        }

        public static Result execute(ModLootStorage storage) {
            Level level = Dungeon.level;
            Hero hero = Dungeon.hero;
            Result result = new Result();
            if (level == null || hero == null) return result;

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
                        result.pickedIntoBags++;
                    } else if (storage != null && storage.absorbOverflow(item)) {
                        result.absorbed++;
                    } else {
                        level.drop(item, mob.pos);
                        break;
                    }
                }
            }

            float end = hero.cooldown();
            hero.spendConstant(start - end);
            return result;
        }
    }

    // --- 內部實作：撿取 ---
    public static class Collect {
        /**
         * @return 真正撿進背包(會播放 ITEM 撿取音效)的件數。
         */
        public static int execute() {
            return execute(null).pickedIntoBags();
        }

        /**
         * 露珠完整交給 Dewdrop.doPickUp(hero, heap.pos) 處理，保留 SPD 原版的水袋、治療、
         * 入口/出口 force 規則與 DEWDROP 音效。一般物品若背包放不下，storage 存在且允許
         * 收納時就直接吸收，不再先搬到英雄腳下再二次掃描。
         */
        public static Result execute(ModLootStorage storage) {
            Level level = Dungeon.level;
            Hero hero = Dungeon.hero;
            Result result = new Result();
            if (level == null || hero == null) return result;

            float start = hero.cooldown();

            if (level.heaps != null) {
                for (Heap heap : level.heaps.valueList()) {
                    if (heap == null) continue;
                    // 舊的無 storage 介面保留原本跳過英雄腳下的行為；共享 storage 路徑則直接處理該格。
                    if (storage == null && heap.pos == hero.pos) continue;
                    if (!isCollectable(heap.type)) continue;

                    while (!heap.isEmpty()) {
                        Item item = heap.peek();
                        if (item == null) break;

                        if (item instanceof Dewdrop) {
                            boolean picked = ((Dewdrop) item).doPickUp(hero, heap.pos);
                            if (!picked) {
                                // 原版露珠在目前狀態不能被消耗時留在原 Heap；不要讓 Loot storage 吸收。
                                break;
                            }

                            Item popped = heap.pickUp();
                            if (popped != null) {
                                GLog.i("Collected: " + popped.name());
                            }
                            // 露珠播 DEWDROP 而非 ITEM、也不算「進背包」。
                            continue;
                        }

                        boolean picked = item.doPickUp(hero, heap.pos);
                        if (picked) {
                            Item popped = heap.pickUp();
                            if (popped != null) {
                                GLog.i("Collected: " + popped.name());
                            }
                            result.pickedIntoBags++;
                            continue;
                        }

                        if (storage != null && ModLootStorage.canStore(item)) {
                            Item popped = heap.pickUp();
                            if (storage.absorbOverflow(popped)) {
                                result.absorbed++;
                                continue;
                            }
                            // Defensive fallback: absorbOverflow should only fail for a non-storable/null item.
                            if (popped != null) {
                                level.drop(popped, heap.pos);
                            }
                            break;
                        }

                        if (storage == null) {
                            // Preserve the legacy helper behavior for any caller that still uses the no-storage API.
                            Item popped = heap.pickUp();
                            if (popped != null) {
                                level.drop(popped, hero.pos);
                            }
                            continue;
                        }

                        // A storage-backed Loot cannot absorb this item (e.g. ModAnkh/Scroll of Loot).
                        // Leave it where it was instead of moving it to the hero's feet.
                        break;
                    }
                }
            }

            float end = hero.cooldown();
            hero.spendConstant(start - end);
            return result;
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
