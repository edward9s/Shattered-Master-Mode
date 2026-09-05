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

    private static PinCushion pinCushion(Mob mob) {
        if (mob == null) {
            return null;
        }
        for (PinCushion pin : mob.buffs(PinCushion.class)) {
            return pin;
        }
        return null;
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
                    PinCushion pc = pinCushion(mob);
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
         *
         * Heap 必須逐件處理，不能因其中一件無法撿取就阻塞整堆。FOR_SALE 是特殊情況：
         * SPD 會把真正待售商品留在 Heap 最後一格，之後掉到同格的普通物品插在前面；
         * Loot 可以處理那些額外物品，但必須永遠保留最後的待售商品。
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

                    boolean normalCollectable = isCollectable(heap.type);
                    boolean saleHeap = heap.type == Heap.Type.FOR_SALE;
                    if (!normalCollectable && !saleHeap) continue;

                    // FOR_SALE 的最後一件是商店真正販售的商品。Heap.drop() 對 FOR_SALE
                    // 一律把後來掉入的物品加到前面，因此只要排除這個 reference，就能安全
                    // Loot 疊在商品上的普通物品而不會偷走未購買商品。
                    Item protectedSaleItem = saleHeap ? heap.items.peekLast() : null;

                    // 使用 snapshot，因為成功撿取/吸收會從原 Heap 刪除目前物品。
                    for (Item item : heap.items.toArray(new Item[0])) {
                        if (item == null || item == protectedSaleItem) {
                            continue;
                        }

                        if (item instanceof Dewdrop) {
                            boolean picked = ((Dewdrop) item).doPickUp(hero, heap.pos);
                            if (!picked) {
                                // 這顆露珠目前不能被原版規則消耗；留在原位，但不要阻塞同 Heap 其他物品。
                                continue;
                            }

                            heap.remove(item);
                            GLog.i("Collected: " + item.name());
                            // 露珠播 DEWDROP 而非 ITEM、也不算「進背包」。
                            continue;
                        }

                        boolean picked = item.doPickUp(hero, heap.pos);
                        if (picked) {
                            heap.remove(item);
                            GLog.i("Collected: " + item.name());
                            result.pickedIntoBags++;
                            continue;
                        }

                        if (storage != null && storage.absorbOverflow(item)) {
                            heap.remove(item);
                            result.absorbed++;
                            continue;
                        }

                        if (storage == null) {
                            // Preserve the legacy helper behavior for any caller that still uses the no-storage API.
                            heap.remove(item);
                            level.drop(item, hero.pos);
                        }
                        // storage-backed Loot 遇到不能收納的物品時只留下它，繼續掃描同 Heap 其他物品。
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
