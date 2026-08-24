package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;

import java.util.ArrayList;

public class ModBuffClass {

    private static final String[] ALLOWED_PACKAGES = new String[]{
            "com.shatteredpixel.shatteredpixeldungeon.actors.buffs.",
            "com.shatteredpixel.shatteredpixeldungeon.plants.",
            "com.shatteredpixel.shatteredpixeldungeon.items.potions.",
            "com.shatteredpixel.shatteredpixeldungeon.items.scrolls.",
            "com.shatteredpixel.shatteredpixeldungeon.items.stones.",
            "com.shatteredpixel.shatteredpixeldungeon.items.spells.",
            "com.shatteredpixel.shatteredpixeldungeon.items.food.",
            "com.shatteredpixel.shatteredpixeldungeon.items.bombs.",
            "com.shatteredpixel.shatteredpixeldungeon.items.wands.",
            "com.shatteredpixel.shatteredpixeldungeon.items.bags."
    };

    private static ArrayList<Class<? extends Buff>> cachedBuffs;

    public ModBuffClass() {
    }

    private static boolean isBlacklistedClass(String className) {
        if (className.contains("PinCushion")) return true;
        if (className.contains("HTBoost")) return true;
        return false;
    }

    public static ArrayList<Class<? extends Buff>> allBuffs() {
        if (cachedBuffs != null) {
            return cachedBuffs;
        }

        cachedBuffs = new ArrayList<>();
        for (Class<? extends Buff> clazz : ModClassScanner.subclassesOf(Buff.class, ALLOWED_PACKAGES)) {
            if (!isBlacklistedClass(clazz.getName())) {
                cachedBuffs.add(clazz);
            }
        }

        return cachedBuffs;
    }
}
