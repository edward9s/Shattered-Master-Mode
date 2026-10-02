package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.spd.mod.journal.WndEnemySurgeInfo;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;

import java.lang.reflect.Method;

/**
 * Permanent Master Mode buff that scales the target game's own natural
 * respawner and can periodically beckon enemies toward the bearer.
 *
 * Respawn mechanics stay in the target game. Source/binary integration hooks
 * pass the vanilla population limit and cooldown through the two static scale
 * methods below, so fork-specific spawn selection and placement remain intact.
 */
public class ModEnemySurge extends Buff {

    private static final String SPAWN_MULTIPLIER = "spawn_multiplier";
    private static final String ATTRACT_ENEMIES = "attract_enemies";

    private static final float ATTRACT_INTERVAL = 6f;

    private int spawnMultiplier = 1;
    private boolean attractEnemies;

    private transient float attractCountdown;

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
    }

    /**
     * Uses Char.buffs(Class), whose erased return type is stable across the
     * supported SPD forks. Older forks expose a different erased return type
     * for Char.buff(Class), so injectable code must not rely on that method.
     */
    public static ModEnemySurge find(Char ch) {
        if (ch == null) {
            return null;
        }
        for (ModEnemySurge surge : ch.buffs(ModEnemySurge.class)) {
            return surge;
        }
        return null;
    }

    private static ModEnemySurge active() {
        for (Char ch : Actor.chars().toArray(new Char[0])) {
            ModEnemySurge surge = find(ch);
            if (surge != null && surge.target != null && surge.target.isAlive()) {
                return surge;
            }
        }
        return null;
    }

    /**
     * Hook for the target game's native respawner population limit.
     */
    public static int scaleMobLimit(int vanillaLimit) {
        ModEnemySurge surge = active();
        if (surge == null || surge.spawnMultiplier <= 1) {
            return vanillaLimit;
        }
        return Math.multiplyExact(vanillaLimit, surge.spawnMultiplier);
    }

    /**
     * Hook for the target game's native respawn cadence.
     */
    public static float scaleRespawnCooldown(float vanillaCooldown) {
        ModEnemySurge surge = active();
        if (surge == null || surge.spawnMultiplier <= 1) {
            return vanillaCooldown;
        }
        return vanillaCooldown / surge.spawnMultiplier;
    }

    public boolean isAttached() {
        return target != null && find(target) == this;
    }

    public void openInfo() {
        if (isAttached()) {
            GameScene.show(new WndEnemySurgeInfo(this));
        }
    }

    /**
     * Source builds may install the richer transparent overlay. Binary injection
     * uses the BuffIndicator click bridge instead, so keep this UI dependency
     * reflective and out of the Enemy Surge gameplay closure.
     */
    private static void ensureOptionalOverlay() {
        invokeOptionalOverlay("ensureInstalled");
    }

    private static void refreshIndicators() {
        BuffIndicator.refreshHero();
        invokeOptionalOverlay("refreshIndicators");
    }

    private static void invokeOptionalOverlay(String methodName) {
        try {
            String className = ModEnemySurge.class.getName().replace(
                    ".mechanics.ModEnemySurge",
                    ".journal.ModEnemySurgeInfoOverlay");
            Class<?> overlay = Class.forName(
                    className, false, ModEnemySurge.class.getClassLoader());
            Method method = overlay.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(null);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Minimal/legacy injection intentionally omits the overlay layer.
        }
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
                ModEnemySurge other = find(ch);
                if (other != null) {
                    other.detach();
                }
            }
        }

        ensureOptionalOverlay();
        return true;
    }

    @Override
    public void fx(boolean on) {
        if (on) {
            ensureOptionalOverlay();
        }
    }

    public int spawnMultiplier() {
        return spawnMultiplier;
    }

    public void setSpawnMultiplier(int multiplier) {
        spawnMultiplier = Math.max(1, Math.min(10, multiplier));
        refreshIndicators();
    }

    public boolean attractEnemies() {
        return attractEnemies;
    }

    public void toggleAttractEnemies() {
        attractEnemies = !attractEnemies;
        attractCountdown = 0f;
        refreshIndicators();
    }

    @Override
    public boolean act() {
        if (target == null || !target.isAlive() || Dungeon.level == null) {
            spend(TICK);
            return true;
        }

        processAttraction();

        spend(TICK);
        return true;
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
        return ModBuffIconCompat.get("RAGE");
    }

    @Override
    public void tintIcon(Image icon) {
        if (attractEnemies) {
            icon.hardlight(0xFF4444);
        } else if (spawnMultiplier >= 4) {
            icon.hardlight(0xFF9F43);
        } else if (spawnMultiplier >= 2) {
            icon.hardlight(0xFFD15C);
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
    public String toString() {
        return name();
    }

    @Override
    public String desc() {
        return "Enemy respawn rate and population limit: " + spawnMultiplier
                + "x. Attraction: " + (attractEnemies ? "ON" : "OFF")
                + ". Tap to configure.";
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
        attractCountdown = 0f;
    }

    @Override
    public void detach() {
        super.detach();
        refreshIndicators();
    }
}
