package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.spd.mod.journal.WndEnemySurgeInfo;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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

    private static Class<?> resolvedLevelClass;
    private static Method mobLimitMethod;
    private static Method mobCountMethod;
    private static Method spawnMobMethod;
    private static Method legacyNMobsMethod;
    private static Method legacyCreateMobMethod;
    private static Method legacyRandomRespawnCellMethod;

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

    public boolean isAttached() {
        return target != null && find(target) == this;
    }

    public void openInfo() {
        if (isAttached()) {
            GameScene.show(new WndEnemySurgeInfo(this));
        }
    }

    private static void refreshIndicators() {
        BuffIndicator.refreshHero();
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

        return true;
    }

    public int spawnMultiplier() {
        return spawnMultiplier;
    }

    public void setSpawnMultiplier(int multiplier) {
        spawnMultiplier = Math.max(1, Math.min(10, multiplier));
        extraSpawnCountdown = Float.NaN;
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
            baseMobLimit = Math.max(0, compatibleMobLimit(Dungeon.level));
        }
        if (baseMobLimit <= 0) {
            extraSpawnCountdown = Float.NaN;
            return;
        }

        int effectiveLimit = baseMobLimit * spawnMultiplier;
        int currentCount = compatibleMobCount(Dungeon.level);
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
            currentCount = compatibleMobCount(Dungeon.level);
            if (currentCount >= effectiveLimit) {
                extraSpawnCountdown = Float.NaN;
                break;
            }

            if (compatibleSpawnMob(Dungeon.level, 12)) {
                attempts++;
                currentCount = compatibleMobCount(Dungeon.level);
                extraSpawnCountdown += extraSpawnInterval(currentCount);
            } else {
                // Match the vanilla spawner's failed-placement retry cadence.
                extraSpawnCountdown = TICK;
                break;
            }
        }
    }

    private static synchronized void resolveLevelCompatibility(Level level) {
        if (level == null) {
            resolvedLevelClass = null;
            mobLimitMethod = null;
            mobCountMethod = null;
            spawnMobMethod = null;
            legacyNMobsMethod = null;
            legacyCreateMobMethod = null;
            legacyRandomRespawnCellMethod = null;
            return;
        }

        Class<?> type = level.getClass();
        if (resolvedLevelClass == type) {
            return;
        }

        resolvedLevelClass = type;
        mobLimitMethod = publicMethod(type, "mobLimit");
        mobCountMethod = publicMethod(type, "mobCount");
        spawnMobMethod = publicMethod(type, "spawnMob", Integer.TYPE);

        legacyNMobsMethod = mobLimitMethod == null
                ? publicMethod(type, "nMobs")
                : null;
        legacyCreateMobMethod = spawnMobMethod == null
                ? publicMethod(type, "createMob")
                : null;
        legacyRandomRespawnCellMethod = spawnMobMethod == null
                ? randomRespawnMethod(type)
                : null;
    }

    private static Method publicMethod(
            Class<?> type, String name, Class<?>... parameters) {
        try {
            Method method = type.getMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private static Method randomRespawnMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (!"randomRespawnCell".equals(method.getName())) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 0
                    || (parameters.length == 1
                    && parameters[0].isAssignableFrom(Mob.class))) {
                try {
                    method.setAccessible(true);
                } catch (SecurityException ignored) {
                }
                return method;
            }
        }
        return null;
    }

    private static int compatibleMobLimit(Level level) {
        resolveLevelCompatibility(level);
        Method method = mobLimitMethod != null
                ? mobLimitMethod
                : legacyNMobsMethod;
        if (method == null) {
            return 0;
        }

        try {
            Object value = method.invoke(level);
            return value instanceof Number
                    ? Math.max(0, ((Number) value).intValue())
                    : 0;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 0;
        }
    }

    private static int compatibleMobCount(Level level) {
        resolveLevelCompatibility(level);
        if (mobCountMethod != null) {
            try {
                Object value = mobCountMethod.invoke(level);
                if (value instanceof Number) {
                    return Math.max(0, ((Number) value).intValue());
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to the stable Level.mobs view.
            }
        }

        int count = 0;
        for (Mob mob : level.mobs.toArray(new Mob[0])) {
            if (mob.alignment == Char.Alignment.ENEMY) {
                count++;
            }
        }
        return count;
    }

    private static boolean compatibleSpawnMob(Level level, int minDistance) {
        resolveLevelCompatibility(level);

        if (spawnMobMethod != null) {
            try {
                Object value = spawnMobMethod.invoke(level, minDistance);
                return value instanceof Boolean && (Boolean) value;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }

        if (legacyCreateMobMethod == null
                || legacyRandomRespawnCellMethod == null
                || Dungeon.hero == null) {
            return false;
        }

        try {
            Object created = legacyCreateMobMethod.invoke(level);
            if (!(created instanceof Mob)) {
                return false;
            }

            Mob mob = (Mob) created;
            Object cellValue;
            if (legacyRandomRespawnCellMethod.getParameterTypes().length == 0) {
                cellValue = legacyRandomRespawnCellMethod.invoke(level);
            } else {
                cellValue = legacyRandomRespawnCellMethod.invoke(level, mob);
            }
            if (!(cellValue instanceof Number)) {
                return false;
            }

            int cell = ((Number) cellValue).intValue();
            if (cell < 0
                    || (minDistance > 0
                    && level.distance(Dungeon.hero.pos, cell) < minDistance)) {
                return false;
            }

            setWanderingState(mob);
            mob.pos = cell;
            GameScene.add(mob);
            return true;

        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static void setWanderingState(Mob mob) {
        try {
            Field state = findField(mob.getClass(), "state");
            Field wandering = findField(mob.getClass(), "WANDERING");
            if (state != null && wandering != null) {
                state.set(mob, wandering.get(mob));
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Default mob state is still valid; placement is the essential step.
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type;
                current != null;
                current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException | SecurityException ignored) {
            }
        }
        return null;
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
        trackedLevel = null;
        baseMobLimit = -1;
        extraSpawnCountdown = Float.NaN;
        attractCountdown = 0f;
    }

    @Override
    public void detach() {
        super.detach();
        refreshIndicators();
    }
}
