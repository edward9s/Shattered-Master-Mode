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
        // Use an explicit kept inner class instead of a lambda/method reference. R8 may horizontally
        // merge lambda synthetics with unrelated Android/library code, which is unsafe to relocate.
        storage.setChangeListener(new Runnable() {
            @Override
            public void run() {
                syncCount();
            }
        });
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
        if (restored instanceof ModLootStorage) {
            storage = (ModLootStorage) restored;
        } else {
            // A missing/corrupt optional storage payload is recoverable. Keeping recovery local also
            // avoids R8 outlining the exception throw into a donor-global synthetic helper.
            storage = new ModLootStorage();
        }

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
            storage.reclaimPending(hero);
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
        if (storage.size() <= 0) {
            return base;
        }

        // Keep the dynamic count formatting inside this kept class rather than allowing Java's
        // string-concat indy to become a donor-global R8 synthetic helper.
        return new StringBuilder(base)
                .append("\n\nCurrently holding ")
                .append(storage.size())
                .append(" item(s). Use Take to retrieve them one at a time, or Dump to empty the scroll at once.")
                .toString();
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
