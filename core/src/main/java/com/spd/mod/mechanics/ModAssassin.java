package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Combo;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.effects.Wound;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sai;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
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
        if (target == null || target == hero || !target.isAlive()) {
            GLog.w("No valid target", new Object[0]);
            return;
        }

        int bestPos = findBestPos(hero, target);

        if (bestPos == -1) {
            GLog.w("No valid attack position", new Object[0]);
            return;
        }

        // 傳送失敗直接中止，絕不在位置未確認的情況下發動攻擊
        if (!ModFlash.perform(hero, bestPos)) {
            return;
        }

        // 以「實際落點」複驗攻擊範圍。bestPos 在搜尋時已驗證過，
        // 此檢查在現行流程中恆成立；保留它是為了保證
        // 「傳送了卻沒攻擊、也沒有任何訊息」這種狀況在本分支永遠不可能發生。
        if (!hero.canAttack(target)) {
            GLog.w("Target is out of reach", new Object[0]);
            hero.spendToWhole();
            return;
        }

        int originalInvisible = hero.invisible;
        hero.invisible = 1;

        Wound.hit(target);
        boolean hit = hero.attack(target);

        // 比照 Hero.onAttackComplete() 的官方原版邏輯：近戰命中時觸發角鬥士連擊與決鬥者連擊計數
        if (hit) {
            if (hero.subClass == HeroSubClass.GLADIATOR) {
                Buff.affect(hero, Combo.class).hit(target);
            }
            if (hero.heroClass == HeroClass.DUELIST) {
                Buff.affect(hero, Sai.ComboStrikeTracker.class).addHit();
            }
        }

        CharSprite sprite = hero.sprite;
        int targetPos = target.pos;
        if (sprite != null) {
            sprite.attack(targetPos);
        }

        hero.invisible = originalInvisible;
        hero.spendToWhole();
    }

    /**
     * 沿「敵人 -> 英雄」路徑掃描所有可站立節點，回傳路徑上離英雄最近端、
     * 且仍可攻擊到敵人的落點 (最遠可攻擊點)。
     * 攻擊可行性完全委託 hero.canAttack：一般武器受 solid 阻擋、
     * 索敵附魔可隔牆隔門，皆由引擎原生 canReach 判定，mod 不另設規則。
     */
    private static int findBestPos(Hero hero, Char target) {
        ArrayList<Integer> path = findSmartPath(hero, target.pos, hero.pos);

        int bestPos = -1;
        int originalPos = hero.pos;
        Level level = Dungeon.level;

        // try/finally 保證 hero.pos 無論如何都會還原，
        // 避免 canAttack 內部拋出例外時英雄殘留在暫代位置
        try {
            for (Integer node : path) {

                // 路徑由 findSmartPath 產生時已排除不可通行與被佔據的格子，
                // 這裡的複檢是最後防線；一旦違反即中斷，不跳格
                Char occupant = Actor.findChar(node);
                boolean isFree = (occupant == null || occupant == hero);
                if (!level.passable[node] || !isFree) {
                    break;
                }

                hero.pos = node;

                // 門格模擬：英雄實際落地時 occupyCell 會開門 (DOOR -> OPEN_DOOR，
                // 不再是 solid)，但此刻模擬時門還關著。canReach 的距離圖以
                // solid 為阻擋，會誤判「站在門口」無法用長距武器攻擊，
                // 導致落點永遠越過門而不停在門口。
                // 故測試門格時暫時視為已開門；只模擬英雄要站的這一格，
                // 路徑上其他關著的門仍是 solid，維持武器不可穿透門板的規則。
                boolean doorSimulated = false;
                boolean originalSolid = false;
                if (level.map[node] == Terrain.DOOR) {
                    originalSolid = level.solid[node];
                    level.solid[node] = false;
                    doorSimulated = true;
                }

                boolean attackable;
                try {
                    attackable = hero.canAttack(target);
                } finally {
                    if (doorSimulated) {
                        level.solid[node] = originalSolid;
                    }
                }

                if (attackable) {
                    bestPos = node;
                }
                // 不在第一次 canAttack 失敗時終止。
                // 「索敵」附魔的武器由 canReach 以純距離判定、可隔牆隔門攻擊，
                // 而路徑是繞牆走的，距敵人的幾何距離沿路徑並非單調遞增——
                // 可能先超出射程、繞過牆後又回到射程內。
                // 一般武器同理：canReach 的距離圖走的是非 solid 地形 (含陷阱、
                // 裂隙上方)，路徑後段的節點仍可能合法地搆到敵人。
                // 故掃描整條路徑，bestPos 為最後一個可攻擊節點，
                // 即路徑上離英雄最近端的最遠可攻擊點。
                // 效能無虞：距離超出射程的節點在 canReach 第一行就被廉價排除。
            }
        } finally {
            hero.pos = originalPos;
        }

        return bestPos;
    }

    /**
     * BFS 尋路：從敵人 (startPos) 往英雄 (heroPos) 探索。
     * - 其他角色所在格視為阻擋，路徑會繞過站在中間的角色，而非中斷放棄
     * - 英雄不可達時，取已探索範圍中幾何距離英雄最近者為終點 (半截路徑)
     * - 路徑回推為確定性演算法：同深度候選一律取最靠英雄的格子，
     *   同一盤面永遠得到同一條路徑，消除舊版依探索順序而變的不穩定行為
     */
    private static ArrayList<Integer> findSmartPath(Hero hero, int startPos, int heroPos) {
        Level level = Dungeon.level;
        int length = level.length();
        int w = level.width();
        int[] offsets = { -1, 1, -w, w, -w - 1, -w + 1, w - 1, w + 1 };

        int[] depth = new int[length];
        for (int i = 0; i < length; i++) {
            depth[i] = -1;
        }

        ArrayList<Integer> queue = new ArrayList<>();
        queue.add(startPos);
        depth[startPos] = 0;

        // 防呆機制：限制最大探索步數，防止極度開闊地形造成效能問題
        int maxExplore = 512;
        int head = 0;

        while (head < queue.size() && maxExplore > 0) {
            int current = queue.get(head++);

            if (current == heroPos) {
                break; // 順利抵達英雄，提早結束
            }

            for (int offset : offsets) {
                int neighbor = current + offset;

                if (neighbor < 0 || neighbor >= length) continue;
                // 幾何距離必須恰為 1，防止 index 運算在地圖左右邊界繞回
                if (level.distance(current, neighbor) != 1) continue;
                if (depth[neighbor] != -1) continue; // 已探索
                if (!level.passable[neighbor]) continue;

                // 被其他角色佔據的格子視為阻擋 (英雄自身除外)
                Char occupant = Actor.findChar(neighbor);
                if (occupant != null && occupant != hero) continue;

                depth[neighbor] = depth[current] + 1;
                queue.add(neighbor);
            }
            maxExplore--;
        }

        // 決定終點：英雄可達就用英雄位置；否則取已探索中
        // 幾何距離英雄最近者 (平手取深度較淺者，完全確定性)
        int goal;
        if (depth[heroPos] > 0) {
            goal = heroPos;
        } else {
            goal = startPos;
            int bestDist = Integer.MAX_VALUE;
            int bestDepth = Integer.MAX_VALUE;
            for (int c : queue) {
                if (c == startPos) continue;
                int d = level.distance(c, heroPos);
                if (d < bestDist || (d == bestDist && depth[c] < bestDepth)) {
                    bestDist = d;
                    bestDepth = depth[c];
                    goal = c;
                }
            }
        }

        ArrayList<Integer> path = new ArrayList<>();
        if (goal == startPos) {
            return path; // 敵人被完全包圍，無路可走
        }

        // 從終點沿深度遞減回推。同深度有多個候選時，
        // 一律選幾何上最靠英雄的格子，確保路徑貼英雄側且結果可重現
        int curr = goal;
        while (depth[curr] > 0) {
            path.add(0, curr); // 往前插入，最後陣列方向是 敵人 -> 英雄

            if (depth[curr] == 1) {
                break; // 再往回就是敵人本格，不列入
            }

            int next = -1;
            int nextDist = Integer.MAX_VALUE;
            for (int offset : offsets) {
                int neighbor = curr + offset;
                if (neighbor < 0 || neighbor >= length) continue;
                if (level.distance(curr, neighbor) != 1) continue;
                if (depth[neighbor] != depth[curr] - 1) continue;

                int d = level.distance(neighbor, heroPos);
                if (d < nextDist) {
                    nextDist = d;
                    next = neighbor;
                }
            }

            if (next == -1) {
                break; // BFS 性質保證必有 depth-1 鄰居，此為防禦性檢查
            }
            curr = next;
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

            if (target == null || target == this.hero) {
                // 空地閃現分支：依設計不檢查牆壁或障礙物，
                // 任何合法地圖點位皆可閃現；落點合理性只有暗殺分支才考慮
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
