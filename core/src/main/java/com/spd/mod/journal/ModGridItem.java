package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.noosa.Image;

import com.spd.mod.ModGame;

public class ModGridItem extends ScrollingGridPane.GridItem {

    private Item item;
    private Weapon.Enchantment enchant;
    private Armor.Glyph glyph;

    public ModGridItem(Image image, Item item, Weapon.Enchantment enchant, Armor.Glyph glyph) {
        super(image);
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
}
