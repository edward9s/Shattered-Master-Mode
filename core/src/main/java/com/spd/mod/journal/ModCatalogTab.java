package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.journal.Catalog;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.watabou.noosa.Image;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.RectF;
import com.watabou.utils.Reflection;

import java.util.ArrayList;

import com.spd.mod.items.*;

public class ModCatalogTab extends Component {

    private ModScrollingGridPane grid;

    // 新增靜態陣列儲存高度，與該實體的專屬索引 ID
    public static float[] savedScrollTops = new float[2];
    private int tabId;

    public ModCatalogTab(ArrayList<Catalog> catalogs, int tabId) {
        super();
        this.tabId = tabId;

        grid = new ModScrollingGridPane();
        add(grid);

        grid.addHeader("Mod Tools");

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
        injectModItem(new ModElixirBrew());
        injectModItem(new ModAnkh());

        for (Catalog catalog : catalogs) {
            grid.addHeader(Messages.titleCase(catalog.title()));

            for (Class<?> clazz : catalog.items()) {
                Object instance = Reflection.newInstance(clazz);
                ModGridItem gridItem = createGridItem(instance);
                if (gridItem != null) {
                    grid.addItem(gridItem);
                }
            }
        }

        // SPD's Catalog is intentionally curated and can omit real runtime
        // classes. Add the discovered difference at the end of each tab.
        if (tabId == 0) {
            addScannedSection("Uncatalogued Equipment", ModCatalogExtras.equipmentItems());
            addScannedSection("Uncatalogued Enchantments", ModCatalogExtras.enchantments());
            addScannedSection("Uncatalogued Glyphs", ModCatalogExtras.glyphs());
        } else if (tabId == 1) {
            addScannedSection("Uncatalogued Items", ModCatalogExtras.consumableItems());
        }
    }

    private void addScannedSection(String title, Iterable<? extends Class<?>> classes) {
        ArrayList<ModGridItem> items = new ArrayList<>();

        for (Class<?> clazz : classes) {
            try {
                Object instance = Reflection.newInstanceUnhandled(clazz);
                if (!hasUsableName(instance)) {
                    continue;
                }

                ModGridItem gridItem = createGridItem(instance);
                if (gridItem != null) {
                    items.add(gridItem);
                }
            } catch (Throwable ignore) {
                // A concrete class can still be runtime-only or require state
                // that makes it unsuitable for direct journal construction.
            }
        }

        if (!items.isEmpty()) {
            grid.addHeader(title);
            for (ModGridItem item : items) {
                grid.addItem(item);
            }
        }
    }

    /**
     * Uncatalogued entries are runtime-discovered, so some are internal helper
     * classes that happen to inherit Item/Enchantment/Glyph. A missing name is
     * a strong signal that SPD never intended the class to be exposed as a
     * usable journal entry. Missing descriptions are allowed so the entry stays
     * usable and long-press can consistently show SPD's NO TEXT FOUND message.
     */
    private boolean hasUsableName(Object instance) {
        if (instance instanceof Item) {
            return ModGridEntry.hasUsableText(((Item) instance).name());
        } else if (instance instanceof Weapon.Enchantment) {
            return ModGridEntry.hasUsableText(((Weapon.Enchantment) instance).name());
        } else if (instance instanceof Armor.Glyph) {
            return ModGridEntry.hasUsableText(((Armor.Glyph) instance).name());
        }
        return false;
    }

    private ModGridItem createGridItem(Object instance) {
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

        return gridItem;
    }

    private void injectModItem(Item item) {
        ModGridItem gridItem = createGridItem(item);
        if (gridItem != null) {
            grid.addItem(gridItem);
        }
    }

    @Override
    public void update() {
        super.update();
        // 寫入對應 ID 的靜態陣列位置
        savedScrollTops[tabId] = grid.content().camera.scroll.y;
    }

    @Override
    public void layout() {
        super.layout();
        grid.setRect(this.x, this.y, this.width, this.height);
    }

    public void restoreScroll() {
        // 從對應 ID 的靜態陣列位置讀取
        grid.scrollTo(0f, savedScrollTops[tabId]);
    }
}
