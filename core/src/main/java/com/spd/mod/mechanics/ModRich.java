package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.bombs.Bomb;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.Key;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.audio.Sample;


public class ModRich {

    public static Weapon.Enchantment pendingEnchantment = null;
    public static Armor.Glyph pendingGlyph = null;

    public static boolean handle(Item item, Weapon.Enchantment enchant, Armor.Glyph glyph) {
        if (item != null) {
            processItem(item);
            return true;
        }
        if (enchant != null) {
            processEnchantment(enchant);
            return true;
        }
        if (glyph != null) {
            processGlyph(glyph);
            return true;
        }
        return false;
    }

    private static void processItem(Item item) {
        try {
            Item newItem = item.getClass().newInstance();
            
            if (newItem instanceof Key) {
                ((Key) newItem).depth = Dungeon.depth;
            }

            if (!(newItem instanceof Bomb)) {
                newItem = newItem.random();
            }

            newItem.cursed = false;
            newItem.identify();

            boolean collected = newItem.collect(Dungeon.hero.belongings.backpack);
            if (!collected) {
                Dungeon.level.drop(newItem, Dungeon.hero.pos);
            }

            GLog.p("Created %s", new Object[]{newItem.name()});
            Sample.INSTANCE.play(Assets.Sounds.ITEM);

        } catch (Exception e) {
        }
    }

    private static void processEnchantment(Weapon.Enchantment enchantment) {
        pendingEnchantment = enchantment;
        pendingGlyph = null;
        ModItemSelector selector = new ModItemSelector("com.spd.mod.mechanics.ModRich");
        GameScene.selectItem(selector);
    }

    private static void processGlyph(Armor.Glyph glyph) {
        pendingEnchantment = null;
        pendingGlyph = glyph;
        ModItemSelector selector = new ModItemSelector("com.spd.mod.mechanics.ModRich");
        GameScene.selectItem(selector);
    }

    public static boolean itemSelectable(Item item) {
        if (pendingEnchantment != null) {
            return item instanceof Weapon;
        }
        if (pendingGlyph != null) {
            return item instanceof Armor;
        }
        return false;
    }

    public static String textPrompt() {
        if (pendingEnchantment != null) {
            return pendingEnchantment.name();
        }
        if (pendingGlyph != null) {
            return pendingGlyph.name();
        }
        return "Select Item";
    }

    public static void onSelect(Item item) {
        if (item == null) {
            pendingEnchantment = null;
            pendingGlyph = null;
            return;
        }

        try {
            if (pendingEnchantment != null && item instanceof Weapon) {
                Weapon weapon = (Weapon) item;
                Weapon.Enchantment enchant = pendingEnchantment.getClass().newInstance();
                weapon.enchant(enchant);
                Item.updateQuickslot();
                Sample.INSTANCE.play(Assets.Sounds.READ);
                GLog.p("Enchanted with %s", new Object[]{pendingEnchantment.name()});
            } else if (pendingGlyph != null && item instanceof Armor) {
                Armor armor = (Armor) item;
                Armor.Glyph glyph = pendingGlyph.getClass().newInstance();
                armor.inscribe(glyph);
                Item.updateQuickslot();
                Sample.INSTANCE.play(Assets.Sounds.READ);
                GLog.p("Inscribed with %s", new Object[]{pendingGlyph.name()});
            }
        } catch (Exception e) {
        }

        ModItemSelector selector = new ModItemSelector("com.spd.mod.mechanics.ModRich");
        GameScene.selectItem(selector);
    }
}
