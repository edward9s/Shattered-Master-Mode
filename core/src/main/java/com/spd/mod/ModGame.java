package com.spd.mod;

import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;

import com.spd.mod.mechanics.ModDepthSelector;
import com.spd.mod.tools.ModLevelSlider;
import com.spd.mod.tools.ModToolsWindow;
import com.spd.mod.mechanics.ModRich;

public class ModGame {

    public ModGame() {
    }

    public static String version() {
        return "0.3.0";
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

    public static void installMenu(Consumer<RedButton> addButton) {
        loadSettings();
        addButton.accept(new ModDepthSelector.OpenBtn());
        addButton.accept(new ModToolsWindow.OpenBtn());
    }

    /**
     * Binary-injection entry point for an already compiled WndGame.
     *
     * The target's private addButton method may be renamed by R8, so the
     * injector only inserts a call to this method. We identify the target
     * method by its stable RedButton parameter descriptor rather than by name.
     */
    public static void installInjectedMenu(Object window) {
        if (window == null) {
            throw new IllegalArgumentException("WndGame is null");
        }

        loadSettings();

        Method addButton = null;
        Method[] methods = window.getClass().getDeclaredMethods();
        for (Method method : methods) {
            Class<?>[] params = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == Void.TYPE
                    && params.length == 1
                    && params[0] == RedButton.class) {
                if (addButton != null) {
                    throw new IllegalStateException(
                            "Ambiguous WndGame RedButton insertion method");
                }
                addButton = method;
            }
        }

        if (addButton == null) {
            throw new IllegalStateException(
                    "WndGame has no single-RedButton insertion method");
        }

        try {
            addButton.setAccessible(true);
            addButton.invoke(window, new ModDepthSelector.OpenBtn());
            addButton.invoke(window, new ModToolsWindow.OpenBtn());
        } catch (Exception e) {
            throw new RuntimeException("Unable to install SMM menu", e);
        }
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
