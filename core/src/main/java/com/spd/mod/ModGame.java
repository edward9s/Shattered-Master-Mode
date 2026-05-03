package com.spd.mod;

import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;

import java.lang.reflect.Method;

import com.spd.mod.tools.ModLevelSlider;
import com.spd.mod.mechanics.ModRich;

public class ModGame {

    public ModGame() {
    }

    public static String version() {
        return "0.1.17";
    }
    
    public static int maxDepth() {
    	return 26;
    }

    public static boolean isAndroid() {
        try {
            Class.forName("android.app.ActivityThread");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static Object getSystemContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method method = activityThreadClass.getMethod("currentApplication");
            return method.invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    public static void loadSettings() {
        ModLevelSlider.load();
    }

    public static void saveSettings() {
        ModLevelSlider.save();
    }

    public static int getModLevel() {
        loadSettings();
        return ModLevelSlider.level;
    }

    public static boolean handleJournalClick(Item item, Weapon.Enchantment enchantment, Armor.Glyph glyph) {
        return ModRich.handle(item, enchantment, glyph);
    }
}
