package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.spd.mod.journal.ModEnemySurgeInfoOverlay;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;

import java.lang.reflect.Field;

/**
 * Permanent Master Mode buff that accelerates normal enemy respawning, raises
 * the natural enemy population limit, and can periodically beckon enemies
 * toward the bearer. Vanilla spawn selection and placement remain untouched.
 */
public class ModEnemySurge extends Buff {

    private static final String SPAWN_MULTIPLIER = "spawn_multiplier";
    private static final String ATTRACT_ENEMIES = "attract_enemies";

    private static final float ATTRACT_INTERVAL = 6f;

    private static Field respawnerField;

    private int spawnMultiplier = 1;
    private boolean attractEnemies;

    private transient Level trackedLevel;
    private transient int baseMobLimit = -1;
    private transient float extraSpawnCountdown = Float.NaN;
    private transient float attractCountdown;

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
    }

    @Override
    public boolean attachTo(Char target) {
        if (!super.attachTo(target)) {
            return false;
        }

        // Enemy Surge changes level-wide spawning, so multiple copies would
        // stack ambiguously. Applying it to a new character transfers the buff.
        for (Char ch : Actor.chars().toArray(new Char[0])) {
            if (ch != target) {
                ModEnemySurge other = ch.buff(ModEnemySurge.class);
                if (other != null) {
                    other.detach();
                }
            }
        }

        ModEnemySurgeInfoOverlay.ensureInstalled();
        return true;
    }

    @Override
    public void fx(boolean on) {
        if (on) {
            ModEnemySurgeInfoOverlay.ensureInstalled();
        }
    }

    public int spawnMultiplier() {
        return spawnMultiplier;
    }

    public void setSpawnMultiplier(int multiplier) {
        spawnMultiplier = Math.max(1, Math.min(10, multiplier));
        extraSpawnCountdown = Float.NaN;
        BuffIndicator.refreshHero();
        ModEnemySurgeInfoOverlay.refreshIndicators();
    }

    public boolean attractEnemies() {
        return attractEnemies;
    }

    public void toggleAttractEnemies() {
        attractEnemies = !attractEnemies;
        attractCountdown = 0f;
        BuffIndicator.refreshHero();
        ModEnemySurgeInfoOverlay.refreshIndicators();
    }

    @Override
    public boolean act() {
        if (target == null || !target.isAlive() || Dungeon.level == null) {
            spend(TICK);
            return true;
        }

        if (trackedLevel != Dungeon.level) {
            trackedLevel = Dungeon.level;
            baseMobLimit = -1;
            extraSpawnCountdown = Float.NaN;
            attractCountdown = 0f;
        }

        processExtraSpawns();
        processAttraction();

        spend(TICK);
        return true;
    }

    private void processExtraSpawns() {
        // 1x is deliberately a complete no-op. Also never create natural
        // spawning on a level where vanilla did not install its own respawner.
        if (spawnMultiplier <= 1 || !hasVanillaRespawner()) {
            extraSpawnCountdown = Float.NaN;
            return;
        }

        // RegularLevel.mobLimit() contains randomness. Sample the vanilla limit
        // only once per visited level so this buff does not consume RNG every turn.
        if (baseMobLimit < 0) {
            baseMobLimit = Math.max(0, Dungeon.level.mobLimit());
        }
        if (baseMobLimit <= 0) {
            extraSpawnCountdown = Float.NaN;
            return;
        }

        int effectiveLimit = baseMobLimit * spawnMultiplier;
        int currentCount = Dungeon.level.mobCount();
        if (currentCount >= effectiveLimit) {
            extraSpawnCountdown = Float.NaN;
            return;
        }

        if (Float.isNaN(extraSpawnCountdown)) {
            extraSpawnCountdown = extraSpawnInterval(currentCount);
        }

        extraSpawnCountdown -= TICK;
        int attempts = 0;

        while (extraSpawnCountdown <= 0f && attempts < spawnMultiplier) {
            currentCount = Dungeon.level.mobCount();
            if (currentCount >= effectiveLimit) {
                extraSpawnCountdown = Float.NaN;
                break;
            }

            if (Dungeon.level.spawnMob(12)) {
                attempts++;
                currentCount = Dungeon.level.mobCount();
                extraSpawnCountdown += extraSpawnInterval(currentCount);
            } else {
                // Match the vanilla spawner's failed-placement retry cadence.
                extraSpawnCountdown = TICK;
                break;
            }
        }
    }

    private float extraSpawnInterval(int currentCount) {
        // Below the vanilla limit, the vanilla MobSpawner still contributes 1x,
        // so this buff supplies only the remaining (N-1)x. Above that limit,
        // vanilla stops spawning and this buff supplies the full Nx rate.
        float extraRate = currentCount < baseMobLimit
                ? spawnMultiplier - 1f
                : spawnMultiplier;
        return Math.max(0.1f, Dungeon.level.respawnCooldown() / extraRate);
    }

    private static boolean hasVanillaRespawner() {
        if (Dungeon.level == null) {
            return false;
        }

        try {
            if (respawnerField == null) {
                respawnerField = Level.class.getDeclaredField("respawner");
                respawnerField.setAccessible(true);
            }
            return respawnerField.get(Dungeon.level) != null;
        } catch (Exception ignored) {
            // If the vanilla implementation changes, fail closed rather than
            // introducing spawning on floors where vanilla may forbid it.
            return false;
        }
    }

    private void processAttraction() {
        if (!attractEnemies) {
            attractCountdown = 0f;
            return;
        }

        attractCountdown -= TICK;
        if (attractCountdown > 0f) {
            return;
        }

        // Beckon enemies toward the bearer without sound or extra particles.
        for (Mob mob : Dungeon.level.mobs.toArray(new Mob[0])) {
            if (mob != target && mob.alignment == Char.Alignment.ENEMY) {
                mob.beckon(target.pos);
            }
        }
        attractCountdown = ATTRACT_INTERVAL;
    }

    @Override
    public int icon() {
        return BuffIndicator.RAGE;
    }

    @Override
    public void tintIcon(Image icon) {
        if (attractEnemies) {
            icon.hardlight(0xFF5577);
        } else {
            icon.hardlight(0x55CCFF);
        }
    }

    @Override
    public String iconTextDisplay() {
        return spawnMultiplier + "x";
    }

    @Override
    public String name() {
        return "Enemy Surge";
    }

    @Override
    public String desc() {
        return "Permanent Master Mode buff. Natural enemy respawning is set to approximately "
                + spawnMultiplier + "x the normal rate, and the natural enemy population limit is also raised to approximately "
                + spawnMultiplier + "x its normal value; 1x leaves both unchanged. "
                + "Open this buff's information window and press the multiplier button to copy the current "
                + "Tools Level / Quantity slider value (1-10) into the buff. The value stays fixed until that button is pressed again. "
                + "Enemy attraction is " + (attractEnemies ? "ON" : "OFF") + ". When enabled, enemies are silently beckoned toward the buff bearer every 6 turns. "
                + "The buff icon is pink while attraction is ON and cyan while it is OFF. "
                + "Only one Enemy Surge can be active at a time; applying it to another character transfers it. "
                + "Vanilla spawn placement and floors with no natural respawner remain unchanged.";
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(SPAWN_MULTIPLIER, spawnMultiplier);
        bundle.put(ATTRACT_ENEMIES, attractEnemies);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        if (bundle.contains(SPAWN_MULTIPLIER)) {
            spawnMultiplier = Math.max(1, Math.min(10, bundle.getInt(SPAWN_MULTIPLIER)));
        }
        if (bundle.contains(ATTRACT_ENEMIES)) {
            attractEnemies = bundle.getBoolean(ATTRACT_ENEMIES);
        }
        trackedLevel = null;
        baseMobLimit = -1;
        extraSpawnCountdown = Float.NaN;
        attractCountdown = 0f;
    }

    @Override
    public void detach() {
        super.detach();
        BuffIndicator.refreshHero();
        ModEnemySurgeInfoOverlay.refreshIndicators();
    }
}
