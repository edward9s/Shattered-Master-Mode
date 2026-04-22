package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.journal.Catalog;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.noosa.Image;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.RectF;
import com.watabou.utils.Reflection;

import java.util.ArrayList;

import com.spd.mod.items.*;

public class ModCatalogTab extends Component {

    private ScrollingGridPane grid;

    public static int currentTab;
    public static float[] scrollTops = new float[2];

    public ModCatalogTab(ArrayList<Catalog> catalogs) {
        super();

        grid = new ScrollingGridPane();
        add(grid);

        grid.addHeader("Mod Items");

        injectModItem(new ModScrollOfAssassin());
        injectModItem(new ModScrollOfBlast());
        injectModItem(new ModScrollOfSight());
        injectModItem(new ModScrollOfLoot());
        injectModItem(new ModScrollOfDisplacement());
        injectModItem(new ModPotionOfResetTier.Tier1());
        injectModItem(new ModPotionOfResetTier.Tier2());
        injectModItem(new ModPotionOfResetTier.Tier3());
        injectModItem(new ModPotionOfResetTier.Tier4());
        injectModItem(new ModPotionOfWeakness());
        injectModItem(new ModBlessedAnkh());

        for (Catalog catalog : catalogs) {
            grid.addHeader(Messages.titleCase(catalog.title()));

            for (Class<?> clazz : catalog.items()) {
                Object instance = Reflection.newInstance(clazz);
                ModGridItem gridItem = null;

                if (instance instanceof Item) {
                    Item item = (Item) instance;
                    ItemSprite sprite = new ItemSprite(item.image, item.glowing());
                    gridItem = new ModGridItem(sprite, item, null, null);

                    if (item.icon != -1) {
                        Image iconImage = new Image(Assets.Sprites.ITEM_ICONS);
                        RectF frame = ItemSpriteSheet.Icons.film.get(item.icon);
                        iconImage.frame(frame);
                        gridItem.addSecondIcon(iconImage);
                    }
                } else if (instance instanceof Weapon.Enchantment) {
                    Weapon.Enchantment enchant = (Weapon.Enchantment) instance;
                    ItemSprite sprite = new ItemSprite(ItemSpriteSheet.WORN_SHORTSWORD, enchant.glowing());
                    gridItem = new ModGridItem(sprite, null, enchant, null);
                } else if (instance instanceof Armor.Glyph) {
                    Armor.Glyph glyph = (Armor.Glyph) instance;
                    ItemSprite sprite = new ItemSprite(ItemSpriteSheet.ARMOR_CLOTH, glyph.glowing());
                    gridItem = new ModGridItem(sprite, null, null, glyph);
                }

                if (gridItem != null) {
                    grid.addItem(gridItem);
                }
            }
        }
    }

    private void injectModItem(Item item) {
        ItemSprite sprite = new ItemSprite(item.image, item.glowing());
        ModGridItem gridItem = new ModGridItem(sprite, item, null, null);

        if (item.icon != -1) {
            Image iconImage = new Image(Assets.Sprites.ITEM_ICONS);
            RectF frame = ItemSpriteSheet.Icons.film.get(item.icon);
            iconImage.frame(frame);
            gridItem.addSecondIcon(iconImage);
        }

        grid.addItem(gridItem);
    }

    @Override
    public void update() {
        super.update();
        float y = grid.content().camera.scroll.y;
        scrollTops[currentTab] = y;
    }

    @Override
    public void layout() {
        super.layout();
        grid.setRect(this.x, this.y, this.width, this.height);
    }

    public void restoreScroll() {
        float y = scrollTops[currentTab];
        grid.scrollTo(0f, y);
    }
}
