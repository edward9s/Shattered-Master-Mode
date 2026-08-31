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

    /**
     * Temporary lending records created when an item is used directly from Loot storage.
     * reclaimLimit is only the maximum amount that may be reclaimed later; missing quantities
     * are assumed to have been consumed, dropped, transformed, or otherwise legitimately moved.
     *
     * Deliberately not serialized: after a save/load, any lent items already in belongings simply
     * become normal inventory items. No quantity is created or destroyed by losing the record.
     */
    private final ArrayList<LentRecord> lent = new ArrayList<>();

    private static class LentRecord {
        final Item template;
        final Item lentItem;
        final int reclaimLimit;
        final boolean stackable;

        LentRecord(Item template, Item lentItem, int reclaimLimit) {
            this.template = template;
            this.lentItem = lentItem;
            this.reclaimLimit = reclaimLimit;
            this.stackable = lentItem.stackable;
        }
    }

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

        boolean reclaimedAny = false;

        for (LentRecord record : lent.toArray(new LentRecord[0])) {
            int reclaimed = 0;

            if (!record.stackable) {
                // Non-stackable items never merge, so preserve the old identity-based behavior.
                if (record.lentItem.quantity() > 0
                        && hero.belongings.backpack.contains(record.lentItem)) {
                    Item returned = record.lentItem.detachAll(hero.belongings.backpack);
                    if (returned != null && returned.quantity() > 0) {
                        reclaimed = Math.min(record.reclaimLimit, returned.quantity());
                        absorb(returned);
                    }
                }
            } else {
                int remaining = record.reclaimLimit;

                // collect() may have merged the lent stack into an existing stack, so reclaim by
                // similarity and quantity rather than by object identity. Snapshot first because
                // detachAll() may mutate bags while we reclaim.
                ArrayList<Item> matches = new ArrayList<>();
                for (Item candidate : hero.belongings.backpack) {
                    if (candidate.quantity() > 0 && record.template.isSimilar(candidate)) {
                        matches.add(candidate);
                    }
                }

                for (Item candidate : matches) {
                    if (remaining <= 0 || candidate.quantity() <= 0) {
                        continue;
                    }

                    int amount = Math.min(remaining, candidate.quantity());
                    Item returned;
                    if (amount == candidate.quantity()) {
                        returned = candidate.detachAll(hero.belongings.backpack);
                    } else {
                        returned = candidate.split(amount);
                    }

                    if (returned == null || returned.quantity() <= 0) {
                        continue;
                    }

                    int returnedQuantity = returned.quantity();
                    absorb(returned);
                    reclaimed += returnedQuantity;
                    remaining -= returnedQuantity;
                }
            }

            if (reclaimed > 0) {
                GLog.i("Returned " + reclaimed + " " + record.template.name() + " to Loot storage.");
                reclaimedAny = true;
            }
        }

        // Any unreclaimed remainder is intentionally forgotten. The recorded quantity is a cap,
        // never a debt that must be recreated or recovered from the floor/world.
        lent.clear();

        if (reclaimedAny) {
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

    /**
     * Temporarily lends the entire selected item/stack to normal belongings and executes its
     * default action. Stackable items intentionally use normal collect()/merge behavior so a lent
     * stack can merge with an existing stack. The recorded quantity is only a later reclaim cap.
     */
    public boolean useItem(Hero hero, Item item) {
        if (hero == null || item == null || !stored.contains(item)) {
            return false;
        }

        if (item instanceof ModReusable) {
            item.execute(hero);
            return true;
        }

        int reclaimLimit = item.quantity();
        Item template = item.duplicate();
        if (template == null || reclaimLimit <= 0) {
            return false;
        }

        stored.remove(item);
        lent.add(new LentRecord(template, item, reclaimLimit));

        // Use the normal collection path first so stackable items merge exactly as ordinary SPD
        // inventory items do. A completely full inventory with no valid merge target must still
        // not lose the lent stack, so preserve the existing force-add fallback.
        if (!item.collect(hero.belongings.backpack)) {
            hero.belongings.backpack.items.add(item);
        }

        changed();
        Item.updateQuickslot();

        Item target = item;
        if (target.quantity() <= 0 || !hero.belongings.contains(target)) {
            target = hero.belongings.getSimilar(template);
        }
        if (target == null) {
            return false;
        }

        target.execute(hero);
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
