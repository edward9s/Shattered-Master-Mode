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
        try {
            String title = null;
            if (item != null) {
                title = item.name();
            } else if (enchant != null) {
                title = enchant.name();
            } else if (glyph != null) {
                title = glyph.name();
            }
            if (ModGridEntry.hasUsableText(title)) {
                return Messages.titleCase(title);
            }
        } catch (Throwable ignore) {
            // Target-specific metadata is optional presentation data.
        }

        Class<?> type = item != null ? item.getClass()
                : enchant != null ? enchant.getClass()
                : glyph != null ? glyph.getClass()
                : null;
        return type != null ? type.getSimpleName() : "Unknown";
    }

    private static String infoDescription(Item item, Weapon.Enchantment enchant, Armor.Glyph glyph) {
        try {
            if (item != null) {
                return item.info();
            } else if (enchant != null) {
                return enchant.desc();
            } else if (glyph != null) {
                return glyph.desc();
            }
        } catch (Throwable ignore) {
            // A beta/fork item may require runtime state to build rich info.
            // Long-press can still open the entry with a neutral description.
        }
        return null;
    }
}
