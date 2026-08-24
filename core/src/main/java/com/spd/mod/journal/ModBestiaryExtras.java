package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.journal.Bestiary;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Finds concrete mobs, traps and plants present in the runtime build but not
 * registered in SPD's curated Bestiary.
 */
public final class ModBestiaryExtras {

    private static ArrayList<Class<? extends Mob>> mobs;
    private static ArrayList<Class<? extends Trap>> traps;
    private static ArrayList<Class<? extends Plant>> plants;

    private ModBestiaryExtras() {
    }

    public static ArrayList<Class<? extends Mob>> mobs() {
        ensureInitialized();
        return mobs;
    }

    public static ArrayList<Class<? extends Trap>> traps() {
        ensureInitialized();
        return traps;
    }

    public static ArrayList<Class<? extends Plant>> plants() {
        ensureInitialized();
        return plants;
    }

    private static synchronized void ensureInitialized() {
        if (mobs != null) {
            return;
        }

        mobs = new ArrayList<>();
        traps = new ArrayList<>();
        plants = new ArrayList<>();

        Set<Class<?>> catalogued = new HashSet<>();
        for (Bestiary bestiary : Bestiary.values()) {
            catalogued.addAll(bestiary.entities());
        }

        // ModClassScanner is already restricted to SPD's namespace. Filtering
        // only by base type is more future-proof than assuming every entity
        // lives under actors.mobs / levels.traps / plants; SPD already has Mob
        // subclasses implemented inside items and hero-ability packages.
        for (Class<? extends Mob> clazz : ModClassScanner.subclassesOf(Mob.class)) {
            if (!catalogued.contains(clazz) && !isBlacklistedClass(clazz.getName())) {
                mobs.add(clazz);
            }
        }

        for (Class<? extends Trap> clazz : ModClassScanner.subclassesOf(Trap.class)) {
            if (!catalogued.contains(clazz) && !isBlacklistedClass(clazz.getName())) {
                traps.add(clazz);
            }
        }

        for (Class<? extends Plant> clazz : ModClassScanner.subclassesOf(Plant.class)) {
            if (!catalogued.contains(clazz) && !isBlacklistedClass(clazz.getName())) {
                plants.add(clazz);
            }
        }
    }

    private static boolean isBlacklistedClass(String className) {
        // Central hook for any concrete runtime helper that proves unsafe to
        // instantiate/spawn from the master-mode bestiary.
        return false;
    }
}
