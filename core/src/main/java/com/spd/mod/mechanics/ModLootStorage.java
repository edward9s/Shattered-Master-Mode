package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.spd.mod.items.ModReusable;
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
    private static final String RECLAIM_TEMPLATE = "reclaim_template";
    private static final String RECLAIM_LIMIT = "reclaim_limit";
    private static final String MOD_ANKH_CLASS = "com.spd.mod.items.ModAnkh";
    private static final String MOD_SCROLL_OF_LOOT_CLASS = "com.spd.mod.items.ModScrollOfLoot";

    private ArrayList<Item> stored = new ArrayList<>();

    /**
     * At most one item-use session can be pending because opening Loot always reclaims before the
     * next selection. The template identifies what may be reclaimed; the limit is only an upper
     * bound, never a debt. Missing quantities are assumed to have been consumed, dropped,
     * transformed, or otherwise legitimately moved.
     *
     * This descriptor is serialized, but the actual lent inventory item is not stored here a
     * second time. The real item remains owned by belongings/world state, so saving this descriptor
     * cannot duplicate item quantity.
     */
    private Item reclaimTemplate;
    private int reclaimLimit;

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

    private static boolean isClassOrSubclassNamed(Object value, String className) {
        if (value == null) {
            return false;
        }
        for (Class<?> cls = value.getClass(); cls != null; cls = cls.getSuperclass()) {
            if (className.equals(cls.getName())) {
                return true;
            }
        }
        return false;
    }

    public static boolean canStore(Item item) {
        return item != null
                && !isClassOrSubclassNamed(item, MOD_SCROLL_OF_LOOT_CLASS)
                && !isClassOrSubclassNamed(item, MOD_ANKH_CLASS);
    }

    private boolean hasPendingReclaim() {
        return reclaimTemplate != null && reclaimLimit > 0;
    }

    private void clearPendingReclaim() {
        reclaimTemplate = null;
        reclaimLimit = 0;
    }

    /**
     * Reclaims up to the recorded limit from currently held similar items. The limit is a cap only:
     * any missing remainder is forgotten and is never recreated or searched for on the floor/world.
     */
    public void reclaimPending(Hero hero) {
        if (!hasPendingReclaim()) {
            clearPendingReclaim();
            return;
        }
        if (hero == null || hero.belongings == null) {
            return;
        }

        Item template = reclaimTemplate;
        int remaining = reclaimLimit;
        int reclaimed = 0;

        // collect() may have merged the used stack with an existing stack or moved it into a
        // specialised bag. Iterate the whole backpack tree and reclaim by similarity + quantity.
        // Snapshot first because detachAll() can mutate bags while reclaiming.
        ArrayList<Item> matches = new ArrayList<>();
        for (Item candidate : hero.belongings.backpack) {
            if (candidate.quantity() > 0 && template.isSimilar(candidate)) {
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

        // Always finish the pending session. Any unreclaimed quantity was consumed, dropped,
        // transformed, or otherwise moved and must never be recreated to satisfy the old limit.
        clearPendingReclaim();

        if (reclaimed > 0) {
            GLog.i("Returned " + reclaimed + " " + template.name() + " to Loot storage.");
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
     * Temporarily moves the entire selected item/stack into normal belongings and executes its
     * default action. Stackable items intentionally use normal collect()/merge behavior. If every
     * bag is full and no merge target exists, force-add keeps the item alive instead of allowing a
     * failed collect to strand or lose it. The recorded quantity is only a later reclaim cap.
     */
    public boolean useItem(Hero hero, Item item) {
        if (hero == null || item == null || !stored.contains(item)) {
            return false;
        }

        if (item instanceof ModReusable) {
            item.execute(hero);
            return true;
        }

        // UI normally guarantees this is empty, but preserve the single-session invariant if this
        // method is ever called directly by another path.
        if (hasPendingReclaim()) {
            reclaimPending(hero);
        }

        int limit = item.quantity();
        Item template = item.duplicate();
        if (template == null || limit <= 0) {
            return false;
        }

        // Record the descriptor before collect(), because a successful merge zeroes the source
        // item's quantity and may make its object identity disappear from belongings.
        reclaimTemplate = template;
        reclaimLimit = limit;
        stored.remove(item);

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
            // Quantity still exists in belongings/world state; abandoning the reclaim descriptor is
            // safer than inventing or deleting anything in an unexpected inventory configuration.
            clearPendingReclaim();
            return false;
        }

        target.execute(hero);
        return true;
    }

    public boolean putSingle(Hero hero, Item item) {
        if (hero == null || !canStore(item)) {
            if (isClassOrSubclassNamed(item, MOD_ANKH_CLASS)) {
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
        if (hasPendingReclaim()) {
            bundle.put(RECLAIM_TEMPLATE, reclaimTemplate);
            bundle.put(RECLAIM_LIMIT, reclaimLimit);
        }
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        stored = new ArrayList<>();
        for (Bundlable b : bundle.getCollection(STORED)) {
            stored.add((Item) b);
        }

        clearPendingReclaim();
        Object template = bundle.get(RECLAIM_TEMPLATE);
        if (template instanceof Item) {
            int limit = bundle.getInt(RECLAIM_LIMIT);
            if (limit > 0) {
                reclaimTemplate = (Item) template;
                reclaimLimit = limit;
            }
        }

        sortStored();
        changed();
    }
}
