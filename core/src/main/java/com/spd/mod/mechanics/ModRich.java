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
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;
import com.watabou.noosa.audio.Sample;

public class ModRich {

    public static Weapon.Enchantment pendingEnchantment = null;
    public static Armor.Glyph pendingGlyph = null;

    public static void openSelector() {
        GameScene.selectItem(new WndBag.ItemSelector() {
            @Override
            public String textPrompt() {
                return ModRich.textPrompt();
            }
            @Override
            public boolean itemSelectable(Item item) {
                return ModRich.itemSelectable(item);
            }
            @Override
            public void onSelect(Item item) {
                ModRich.onSelect(item);
            }
        });
    }

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
        openSelector();
    }

    private static void processGlyph(Armor.Glyph glyph) {
        pendingEnchantment = null;
        pendingGlyph = glyph;
        openSelector();
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
                if (weapon.enchantment != null && weapon.enchantment.getClass() == pendingEnchantment.getClass()) {
                    weapon.enchant(null);
                    GLog.p("Removed %s", new Object[]{pendingEnchantment.name()});
                } else {
                    Weapon.Enchantment enchant = pendingEnchantment.getClass().newInstance();
                    weapon.enchant(enchant);
                    GLog.p("Enchanted with %s", new Object[]{pendingEnchantment.name()});
                }
                Item.updateQuickslot();
                Sample.INSTANCE.play(Assets.Sounds.READ);
            } else if (pendingGlyph != null && item instanceof Armor) {
                Armor armor = (Armor) item;
                if (armor.glyph != null && armor.glyph.getClass() == pendingGlyph.getClass()) {
                    armor.inscribe(null);
                    GLog.p("Removed %s", new Object[]{pendingGlyph.name()});
                } else {
                    Armor.Glyph glyph = pendingGlyph.getClass().newInstance();
                    armor.inscribe(glyph);
                    GLog.p("Inscribed with %s", new Object[]{pendingGlyph.name()});
                }
                Item.updateQuickslot();
                Sample.INSTANCE.play(Assets.Sounds.READ);
            }
        } catch (Exception e) {
        }

        openSelector();
    }
}
