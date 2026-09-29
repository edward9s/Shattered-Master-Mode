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
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.utils.Callback;

import java.util.ArrayList;

public class ModAssassin {

    public static void cast(Hero hero) {
        GameScene.selectCell(new Selector(hero));
    }

    /**
     * Core Assassinate execution is Char-generic. The player-facing
     * ModAssassinate buff remains Hero-only because its targeting UI is owned by
     * Dungeon.hero; non-Hero callers must supply their own activation policy.
     */
    public static void perform(Char attacker, Char target) {
        if (attacker == null
                || target == null
                || target == attacker
                || !attacker.isAlive()
                || !target.isAlive()) {
            GLog.w("No valid target", new Object[0]);
            return;
        }

        int bestPos = findBestPos(attacker, target);

        if (bestPos == -1) {
            GLog.w("No valid attack position", new Object[0]);
            return;
        }

        // 傳送失敗直接中止，絕不在位置未確認的情況下發動攻擊
        if (!ModFlash.perform(attacker, bestPos)) {
            return;
        }

        // 以「實際落點」複驗攻擊範圍。bestPos 在搜尋時已驗證過。
        if (!attacker.canAttack(target)) {
            GLog.w("Target is out of reach", new Object[0]);
            return;
        }

        Wound.hit(target);

        int originalInvisible = attacker.invisible;
        boolean hit;

        try {
            // Assassinate creates the same hit context as an invisible attack, then
            // lets the target Char implementation decide whether surprise accuracy
            // applies. Force Hit is resolved centrally by the injected Char.hit hook.
            attacker.invisible = Math.max(1, originalInvisible);
            hit = attacker.attack(target, 1f, 0f, 1f);
        } finally {
            attacker.invisible = originalInvisible;
        }

        if (!hit && target.isAlive()) {
            // Instant Kill owns its engine-blocked-attack policy independently.
            hit = ModInstantKill.resolveBlockedAttack(attacker, target);
        }

        // Hero has class-specific onAttackComplete bookkeeping that this synchronous,
        // turn-free attack intentionally bypasses. Preserve only those Hero semantics.
        if (hit && attacker instanceof Hero) {
            Hero hero = (Hero) attacker;
            if (hero.subClass == HeroSubClass.GLADIATOR) {
                Buff.affect(hero, Combo.class).hit(target);
            }
            if (hero.heroClass == HeroClass.DUELIST) {
                ModCombatCompat.addDuelistComboHit(hero, target);
            }
        }

        CharSprite sprite = attacker.sprite;
        int targetPos = target.pos;
        if (sprite != null) {
            // Damage is already resolved synchronously. A no-op callback keeps this
            // animation visual-only and prevents Hero.onAttackComplete() from charging
            // a normal attack turn on forks where CharSprite does that automatically.
            sprite.attack(targetPos, new Callback() {
                @Override
                public void call() {
                    // Assassinate intentionally stays in the same actor tick.
                }
            });
        }

        // Assassinate is intentionally turn-free. Do not spend or round actor time.
    }

    /**
     * 沿「目標 -> 攻擊者」路徑掃描所有可站立節點，回傳最靠攻擊者一端、
     * 且仍可攻擊目標的落點。攻擊可行性完全委託 attacker.canAttack()。
     */
    private static int findBestPos(Char attacker, Char target) {
        ArrayList<Integer> path = findSmartPath(attacker, target.pos, attacker.pos);

        int bestPos = -1;
        int originalPos = attacker.pos;
        Level level = Dungeon.level;

        // try/finally 保證暫時模擬位置一定還原。
        try {
            for (Integer node : path) {
                Char occupant = Actor.findChar(node);
                boolean isFree = occupant == null || occupant == attacker;
                if (!level.passable[node] || !isFree) {
                    break;
                }

                attacker.pos = node;

                // 模擬角色實際站上門格後門已打開，避免 canAttack() 因目前
                // solid 狀態錯判門格上的合法攻擊位置。
                boolean doorSimulated = false;
                boolean originalSolid = false;
                if (level.map[node] == Terrain.DOOR) {
                    originalSolid = level.solid[node];
                    level.solid[node] = false;
                    doorSimulated = true;
                }

                boolean attackable;
                try {
                    attackable = attacker.canAttack(target);
                } finally {
                    if (doorSimulated) {
                        level.solid[node] = originalSolid;
                    }
                }

                if (attackable) {
                    bestPos = node;
                }
            }
        } finally {
            attacker.pos = originalPos;
        }

        return bestPos;
    }

    /**
     * BFS 尋路：從目標 (startPos) 往攻擊者 (attackerPos) 探索。
     * - 其他角色所在格視為阻擋
     * - 攻擊者不可達時，取已探索範圍中幾何距離攻擊者最近者
     * - 同深度候選固定取最靠攻擊者者，維持結果可重現
     */
    private static ArrayList<Integer> findSmartPath(
            Char attacker, int startPos, int attackerPos) {
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

        int maxExplore = 512;
        int head = 0;

        while (head < queue.size() && maxExplore > 0) {
            int current = queue.get(head++);

            if (current == attackerPos) {
                break;
            }

            for (int offset : offsets) {
                int neighbor = current + offset;

                if (neighbor < 0 || neighbor >= length) continue;
                if (level.distance(current, neighbor) != 1) continue;
                if (depth[neighbor] != -1) continue;
                if (!level.passable[neighbor]) continue;

                Char occupant = Actor.findChar(neighbor);
                if (occupant != null && occupant != attacker) continue;

                depth[neighbor] = depth[current] + 1;
                queue.add(neighbor);
            }
            maxExplore--;
        }

        int goal;
        if (depth[attackerPos] > 0) {
            goal = attackerPos;
        } else {
            goal = startPos;
            int bestDist = Integer.MAX_VALUE;
            int bestDepth = Integer.MAX_VALUE;
            for (int cell : queue) {
                if (cell == startPos) continue;
                int distance = level.distance(cell, attackerPos);
                if (distance < bestDist
                        || (distance == bestDist && depth[cell] < bestDepth)) {
                    bestDist = distance;
                    bestDepth = depth[cell];
                    goal = cell;
                }
            }
        }

        ArrayList<Integer> path = new ArrayList<>();
        if (goal == startPos) {
            return path;
        }

        int current = goal;
        while (depth[current] > 0) {
            path.add(0, current);

            if (depth[current] == 1) {
                break;
            }

            int next = -1;
            int nextDist = Integer.MAX_VALUE;
            for (int offset : offsets) {
                int neighbor = current + offset;
                if (neighbor < 0 || neighbor >= length) continue;
                if (level.distance(current, neighbor) != 1) continue;
                if (depth[neighbor] != depth[current] - 1) continue;

                int distance = level.distance(neighbor, attackerPos);
                if (distance < nextDist) {
                    nextDist = distance;
                    next = neighbor;
                }
            }

            if (next == -1) {
                break;
            }
            current = next;
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

            if (target == null || target == hero) {
                // Player UI remains Hero-owned. Empty cells use the existing Flash
                // behavior; Assassinate itself delegates to the Char-generic core.
                ModFlash.perform(hero, cell);
            } else {
                ModAssassin.perform(hero, target);
            }
        }

        @Override
        public String prompt() {
            return "Select target or cell";
        }
    }
}
