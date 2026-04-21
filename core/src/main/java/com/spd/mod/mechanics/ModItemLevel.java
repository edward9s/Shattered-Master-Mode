package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.audio.Sample;

import com.spd.mod.ModGame;

public class ModItemLevel {

    public static int opMode;

    public ModItemLevel() {
    }

    public static void doUpgrade(Item item, int count) {
        for (int i = 0; i < count; i++) {
            item.upgrade();
        }
    }

    public static void doDegrade(Item item, int count) {
        for (int i = 0; i < count; i++) {
            item.degrade();
        }
    }

    public static String textPrompt() {
        if (opMode == 1) {
            return "Select item to Upgrade / Add";
        } else {
            return "Select item to Downgrade / Remove";
        }
    }

    public static boolean itemSelectable(Item item) {
        return true;
    }

    public static void onSelect(Item item) {
        if (item == null) {
            return;
        }

        if (!item.stackable || item.isUpgradable()) {
            Weapon.Enchantment enchant = null;
            Armor.Glyph glyph = null;

            if (item instanceof Weapon) {
                enchant = ((Weapon) item).enchantment;
            } else if (item instanceof Armor) {
                glyph = ((Armor) item).glyph;
            }

            int count = ModGame.getModLevel();

            if (opMode == 1) {
                doUpgrade(item, count);
            } else {
                doDegrade(item, count);
            }

            if (enchant != null) {
                ((Weapon) item).enchant(enchant);
            }
            if (glyph != null) {
                ((Armor) item).inscribe(glyph);
            }

            Sample.INSTANCE.play(Assets.Sounds.READ);
        } else {
            int delta = ModGame.getModLevel();
            int current = item.quantity();

            if (opMode == 1) {
                current += delta;
            } else {
                current -= delta;
            }

            item.quantity(current);
            Sample.INSTANCE.play(Assets.Sounds.ITEM);
        }

        Item.updateQuickslot();
        GLog.p("Modified: %s", new Object[]{item.name()});

        ModItemSelector selector = new ModItemSelector("com.spd.mod.mechanics.ModItemLevel");
        GameScene.selectItem(selector);
    }
}
