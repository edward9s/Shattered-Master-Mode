package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;

import java.lang.reflect.Method;

public class ModItemSelector extends WndBag.ItemSelector {

    private String targetClass;

    public ModItemSelector(String targetClass) {
        super();
        this.targetClass = targetClass;
    }

    @Override
    public String textPrompt() {
        try {
            Class<?> clazz = Class.forName(this.targetClass);
            Method method = clazz.getMethod("textPrompt");
            return (String) method.invoke(null);
        } catch (Exception e) {
            return "Select Item";
        }
    }

    @Override
    public boolean itemSelectable(Item item) {
        try {
            Class<?> clazz = Class.forName(this.targetClass);
            Method method = clazz.getMethod("itemSelectable", Item.class);
            return (Boolean) method.invoke(null, item);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onSelect(Item item) {
        try {
            Class<?> clazz = Class.forName(this.targetClass);
            Method method = clazz.getMethod("onSelect", Item.class);
            method.invoke(null, item);
        } catch (Exception e) {
        }
    }
}
