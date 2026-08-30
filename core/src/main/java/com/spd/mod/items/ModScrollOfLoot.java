package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.ScrollHolder;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.spd.mod.mechanics.ModLootStorage;
import com.watabou.utils.Bundle;

public class ModScrollOfLoot extends Scroll {

    private static final String STORAGE = "storage";

    private ModLootStorage storage = new ModLootStorage();

    public ModScrollOfLoot() {
        super();
        bindStorage();
    }

    private void bindStorage() {
        storage.setChangeListener(this::syncCount);
    }

    private void syncCount() {
        this.level(storage == null ? 0 : storage.size());
    }

    public ModLootStorage storage() {
        return storage;
    }

    @Override
    public boolean keptThroughLostInventory() {
        return true;
    }

    @Override
    public boolean collect(Bag container) {
        if (container instanceof ScrollHolder) {
            return false;
        }
        return super.collect(container);
    }

    @Override
    public void reset() {
        super.reset();
        this.image = ItemSpriteSheet.SCROLL_HOLDER;
        this.icon = ItemSpriteSheet.Icons.RING_WEALTH;
        this.rune = "scroll_loot";
        this.stackable = false;
        this.keptThoughLostInvent = true;
        this.unique = true;
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(STORAGE, storage);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        this.level(0);
        super.restoreFromBundle(bundle);

        Object restored = bundle.get(STORAGE);
        if (!(restored instanceof ModLootStorage)) {
            throw new IllegalStateException("Scroll of Loot bundle has no valid Loot storage");
        }
        storage = (ModLootStorage) restored;

        bindStorage();
        reset();
        syncCount();
    }

    @Override
    public String name() {
        return "Scroll of Loot";
    }

    @Override
    public void execute(Hero hero, String action) {
        if ("READ".equals(action)) {
            storage.reclaimLent(hero);
            GameScene.show(new WndModLoot(storage, name(), WndModLoot.Mode.USE));
        } else {
            super.execute(hero, action);
        }
    }

    @Override
    public void doRead() {
        storage.loot(Dungeon.hero);
    }

    @Override
    public String desc() {
        String base = "Reading it opens a panel of the items held inside that have a default action,"
                + " so you can eat, read, drink or zap them straight out of the scroll."
                + "\n\nLoot makes the hero trample all high-grass and loot all heaps."
                + " Items that don't fit in your bags are absorbed into the scroll."
                + " You can also Put items from your bags into the scroll for safekeeping.";
        if (storage.size() > 0) {
            base += "\n\nCurrently holding " + storage.size()
                    + " item(s). Use Take to retrieve them one at a time, or Dump to empty the scroll at once.";
        }
        return base;
    }

    @Override
    public int value() {
        return 0;
    }

    @Override
    public boolean isIdentified() {
        return true;
    }

    @Override
    public boolean isKnown() {
        return true;
    }

    @Override
    public void setKnown() {
        // Block Scroll's global registration; this mod scroll is always known.
    }
}
