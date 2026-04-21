package com.spd.mod.tools;

import java.util.prefs.Preferences;

import com.shatteredpixel.shatteredpixeldungeon.ui.OptionSlider;

import com.spd.mod.ModGame;

public class ModLevelSlider extends OptionSlider {

    public static int level = 1;

    public static void load() {
        Preferences prefs = Preferences.userRoot().node("spd_mod");
        level = prefs.getInt("mod_level", 1);
    }

    public static void save() {
        Preferences prefs = Preferences.userRoot().node("spd_mod");
        prefs.putInt("mod_level", level);
    }

    public ModLevelSlider() {
        super("Level / Quantity", "1", "10", 1, 10);
        setSelectedValue(level);
    }

    @Override
    protected void onChange() {
        level = getSelectedValue();
        ModGame.saveSettings();
    }
}
