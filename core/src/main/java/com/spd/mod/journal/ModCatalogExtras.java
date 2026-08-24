package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.journal.Catalog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Finds concrete item/enchantment/glyph classes that exist in the game build
 * but are not registered in SPD's Journal Catalog.
 */
public final class ModCatalogExtras {

    private static final String ITEMS_PACKAGE = "com.shatteredpixel.shatteredpixeldungeon.items.";

    private static ArrayList<Class<? extends Item>> equipmentItems;
    private static ArrayList<Class<? extends Item>> consumableItems;
    private static ArrayList<Class<? extends Weapon.Enchantment>> enchantments;
    private static ArrayList<Class<? extends Armor.Glyph>> glyphs;

    private ModCatalogExtras() {
    }

    public static ArrayList<Class<? extends Item>> equipmentItems() {
        ensureInitialized();
        return equipmentItems;
    }

    public static ArrayList<Class<? extends Item>> consumableItems() {
        ensureInitialized();
        return consumableItems;
    }

    public static ArrayList<Class<? extends Weapon.Enchantment>> enchantments() {
        ensureInitialized();
        return enchantments;
    }

    public static ArrayList<Class<? extends Armor.Glyph>> glyphs() {
        ensureInitialized();
        return glyphs;
    }

    private static synchronized void ensureInitialized() {
        if (equipmentItems != null) {
            return;
        }

        equipmentItems = new ArrayList<>();
        consumableItems = new ArrayList<>();
        enchantments = new ArrayList<>();
        glyphs = new ArrayList<>();

        Set<Class<?>> catalogued = new HashSet<>();
        for (Catalog catalog : Catalog.values()) {
            catalogued.addAll(catalog.items());
        }

        for (Class<? extends Item> clazz : ModClassScanner.subclassesOf(Item.class, ITEMS_PACKAGE)) {
            if (!catalogued.contains(clazz) && !isBlacklistedClass(clazz.getName())) {
                if (isEquipmentItem(clazz)) {
                    equipmentItems.add(clazz);
                } else {
                    consumableItems.add(clazz);
                }
            }
        }

        for (Class<? extends Weapon.Enchantment> clazz :
                ModClassScanner.subclassesOf(Weapon.Enchantment.class, ITEMS_PACKAGE)) {
            if (!catalogued.contains(clazz) && !isBlacklistedClass(clazz.getName())) {
                enchantments.add(clazz);
            }
        }

        for (Class<? extends Armor.Glyph> clazz :
                ModClassScanner.subclassesOf(Armor.Glyph.class, ITEMS_PACKAGE)) {
            if (!catalogued.contains(clazz) && !isBlacklistedClass(clazz.getName())) {
                glyphs.add(clazz);
            }
        }
    }

    private static boolean isEquipmentItem(Class<?> clazz) {
        if (Weapon.class.isAssignableFrom(clazz) || Armor.class.isAssignableFrom(clazz)) {
            return true;
        }

        String name = clazz.getName();
        return name.startsWith(ITEMS_PACKAGE + "artifacts.")
                || name.startsWith(ITEMS_PACKAGE + "bags.")
                || name.startsWith(ITEMS_PACKAGE + "rings.")
                || name.startsWith(ITEMS_PACKAGE + "trinkets.")
                || name.startsWith(ITEMS_PACKAGE + "wands.")
                || name.startsWith(ITEMS_PACKAGE + "weapon.")
                || name.startsWith(ITEMS_PACKAGE + "armor.");
    }

    private static boolean isBlacklistedClass(String className) {
        // Keep this hook centralized for any concrete runtime-only helper classes
        // that prove unsafe or meaningless to instantiate from the journal.
        return false;
    }
}
