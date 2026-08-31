package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.spd.mod.items.ModAnkh;
import com.spd.mod.items.ModReusable;
import com.spd.mod.items.ModScrollOfLoot;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;

import java.util.ArrayList;

/**
 * Shared state and behavior for Scroll of Loot and the permanent Loot buff.
 * This class owns the stored items; UI and item/buff identity live elsewhere.
 */
public class ModLootStorage implements Bundlable {

    private static final String STORED = "stored";

    private ArrayList<Item> stored = new ArrayList<>();

    // Items temporarily lent to the hero so their normal execute() path can consume them.
    // Deliberately not serialized: if the game is saved, the lent item is already in belongings.
    private final ArrayList<Item> lent = new ArrayList<>();

    private transient Runnable changeListener;

    public void setChangeListener(Runnable listener) {
        changeListener = listener;
        changed();
    }

    private void changed() {
        if (changeListener != null) {
            changeListener.run();
        }
    }

    public ArrayList<Item> getStored() {
        return stored;
    }

    public int size() {
        return stored.size();
    }

    public ArrayList<Item> getUsable(Hero hero) {
        ArrayList<Item> usable = new ArrayList<>();
        for (Item item : stored) {
            if (isUsable(hero, item)) {
                usable.add(item);
            }
        }
        return usable;
    }

    private boolean isUsable(Hero hero, Item item) {
        if (item == null || hero == null) {
            return false;
        }

        String action = item.defaultAction();
        if (action == null || item instanceof Bag) {
            return false;
        }
        if (ModItemKind.is(item, ModItemKind.WAND)
                || ModItemKind.is(item, ModItemKind.MISSILE_WEAPON)) {
            return false;
        }

        ArrayList<String> actions = item.actions(hero);
        return actions != null && actions.contains(action);
    }

    public static boolean canStore(Item item) {
        return item != null
                && !(item instanceof ModScrollOfLoot)
                && !(item instanceof ModAnkh);
    }

    public void reclaimLent(Hero hero) {
        if (lent.isEmpty()) {
            return;
        }
        if (hero == null || hero.belongings == null) {
            lent.clear();
            return;
        }

        boolean reclaimed = false;
        for (Item item : lent.toArray(new Item[0])) {
            if (item.quantity() > 0 && hero.belongings.contains(item)) {
                item.detachAll(hero.belongings.backpack);
                absorb(item);
                GLog.i("Returned " + item.name() + " to Loot storage.");
                reclaimed = true;
            }
        }
        lent.clear();

        if (reclaimed) {
            sortStored();
            changed();
            Item.updateQuickslot();
        }
    }

    public void loot(Hero hero) {
        if (hero == null || hero != Dungeon.hero) {
            return;
        }

        ModLoot.Result result = ModLoot.grabItems(this);
        ModLoot.trampleGrass();
        result.add(ModLoot.collectHeaps(this));

        if (result.absorbed() > 0) {
            sortStored();
            GLog.i("Absorbed " + result.absorbed() + " item(s) into Loot storage.");
            if (result.pickedIntoBags() == 0) {
                Sample.INSTANCE.play(Assets.Sounds.ITEM);
            }
        }
        changed();
    }

    /**
     * Internal overflow target used by ModLoot. This deliberately does not sort or notify for every item;
     * loot() batches those updates after the full-map operation has finished.
     */
    boolean absorbOverflow(Item item) {
        if (!canStore(item)) {
            return false;
        }
        absorb(item);
        return true;
    }

    private void absorb(Item item) {
        if (item.stackable) {
            for (Item existing : stored) {
                if (existing.isSimilar(item)) {
                    existing.merge(item);
                    return;
                }
            }
        }
        stored.add(item);
    }

    private void sortStored() {
        ModItemOrder.sort(stored);
    }

    /** Takes the selected stored item (including its full stack, when stackable) out of Loot storage. */
    public boolean takeItem(Hero hero, Item item) {
        if (hero == null || item == null || !stored.contains(item)) {
            return false;
        }

        if (item.collect(hero.belongings.backpack)) {
            stored.remove(item);
            GLog.i("Took " + item.name() + " from Loot storage.");
        } else {
            Dungeon.level.drop(item, hero.pos).sprite.drop();
            stored.remove(item);
            GLog.w("Dropped " + item.name() + " on the floor (backpack full).");
        }

        Sample.INSTANCE.play(Assets.Sounds.ITEM);
        changed();
        Item.updateQuickslot();
        return true;
    }

    public boolean useItem(Hero hero, Item item) {
        if (hero == null || item == null || !stored.contains(item)) {
            return false;
        }

        if (item instanceof ModReusable) {
            item.execute(hero);
            return true;
        }

        // Lend the entire stored item/stack so stackable consumables can be used repeatedly
        // through their normal execute() flow. Keep it as a distinct backpack entry instead of
        // collecting/merging it, so reclaimLent() can still track the same object afterwards.
        stored.remove(item);
        hero.belongings.backpack.items.add(item);
        lent.add(item);
        changed();
        Item.updateQuickslot();

        item.execute(hero);
        return true;
    }

    public boolean putSingle(Hero hero, Item item) {
        if (hero == null || !canStore(item)) {
            if (item instanceof ModAnkh) {
                GLog.w(item.name() + " can't be stored in Loot storage.");
            }
            return false;
        }

        if (item.isEquipped(hero) && !((EquipableItem) item).doUnequip(hero, false)) {
            GLog.w("Can't unequip " + item.name() + ".");
            return false;
        }

        item.detachAll(hero.belongings.backpack);
        absorb(item);
        sortStored();
        GLog.i("Stored " + item.name() + " in Loot storage.");
        Sample.INSTANCE.play(Assets.Sounds.ITEM);

        changed();
        Item.updateQuickslot();
        return true;
    }

    public void dump(Hero hero) {
        if (hero == null || Dungeon.level == null) {
            return;
        }

        int released = 0;
        int dropped = 0;

        for (Item item : stored.toArray(new Item[0])) {
            if (item.collect(hero.belongings.backpack)) {
                released++;
            } else {
                Dungeon.level.drop(item, hero.pos).sprite.drop();
                dropped++;
            }
        }
        stored.clear();

        if (dropped > 0) {
            GLog.w("Dumped " + released + " item(s) into your bags; " + dropped
                    + " dropped on the floor (backpack full).");
        } else if (released > 0) {
            GLog.i("Dumped " + released + " item(s) into your bags.");
        }
        if (released + dropped > 0) {
            Sample.INSTANCE.play(Assets.Sounds.ITEM);
        }
        changed();
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        bundle.put(STORED, stored);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        stored = new ArrayList<>();
        for (Bundlable b : bundle.getCollection(STORED)) {
            stored.add((Item) b);
        }
        lent.clear();
        sortStored();
        changed();
    }
}
