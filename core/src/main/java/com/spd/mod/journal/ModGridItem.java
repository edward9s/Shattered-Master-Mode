package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.watabou.noosa.Image;

import com.spd.mod.ModGame;

public class ModGridItem extends ModGridEntry {

    private Item item;
    private Weapon.Enchantment enchant;
    private Armor.Glyph glyph;

    public ModGridItem(Image image, Item item, Weapon.Enchantment enchant, Armor.Glyph glyph) {
        super(image, infoTitle(item, enchant, glyph), infoDescription(item, enchant, glyph));
        this.item = item;
        this.enchant = enchant;
        this.glyph = glyph;
    }

    @Override
    public boolean onClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ModGame.handleJournalClick(this.item, this.enchant, this.glyph);
        return true;
    }

    private static String infoTitle(Item item, Weapon.Enchantment enchant, Armor.Glyph glyph) {
        if (item != null) {
            return Messages.titleCase(item.name());
        } else if (enchant != null) {
            return Messages.titleCase(enchant.name());
        } else if (glyph != null) {
            return Messages.titleCase(glyph.name());
        }
        return null;
    }

    private static String infoDescription(Item item, Weapon.Enchantment enchant, Armor.Glyph glyph) {
        if (item != null) {
            return item.info();
        } else if (enchant != null) {
            return enchant.desc();
        } else if (glyph != null) {
            return glyph.desc();
        }
        return null;
    }
}
