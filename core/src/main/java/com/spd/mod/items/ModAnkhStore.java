package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;

import java.util.ArrayList;

/**
 * Self-contained storage and Put/Take UI for ModAnkh.
 * This class is a first-class APK-injection payload and has no ModDebug dependency.
 */
public final class ModAnkhStore {

    private static final String STORED = "stored";
    private static final int TAKE_PAGE_SIZE = 8;

    private final ArrayList<Item> stored = new ArrayList<>();
    private int takePageStart = 0;

    public void storeInBundle(Bundle bundle) {
        bundle.put(STORED, stored);
    }

    public void restoreFromBundle(Bundle bundle) {
        stored.clear();
        takePageStart = 0;
        for (Bundlable value : bundle.getCollection(STORED)) {
            if (value instanceof Item) {
                stored.add((Item) value);
            }
        }
    }

    public boolean isEmpty() {
        return stored.isEmpty();
    }

    public int size() {
        return stored.size();
    }

    public void showPutSelector(final Item owner, final Hero hero) {
        if (owner == null || hero == null || hero.belongings == null
                || hero.belongings.backpack == null) {
            return;
        }

        GameScene.selectItem(new WndBag.ItemSelector() {
            @Override
            public String textPrompt() {
                return "Select an item to store";
            }

            @Override
            public boolean itemSelectable(Item item) {
                return canStore(owner, item);
            }

            @Override
            public void onSelect(Item item) {
                if (item != null && putSingle(owner, hero, item)) {
                    showPutSelector(owner, hero);
                }
            }
        });
    }

    public void showTakeSelector(final Item owner, final Hero hero) {
        showTakeSelector(owner, hero, takePageStart);
    }

    private void showTakeSelector(
            final Item owner,
            final Hero hero,
            int requestedStart) {
        if (owner == null || hero == null || stored.isEmpty()) {
            takePageStart = 0;
            return;
        }

        final int lastPageStart = ((stored.size() - 1) / TAKE_PAGE_SIZE) * TAKE_PAGE_SIZE;
        final int start = Math.max(0, Math.min(requestedStart, lastPageStart));
        takePageStart = start;
        final int end = Math.min(stored.size(), start + TAKE_PAGE_SIZE);
        final ArrayList<Item> page = new ArrayList<>(stored.subList(start, end));
        final boolean hasPrevious = start > 0;
        final boolean hasNext = end < stored.size();
        final int previousIndex = hasPrevious ? page.size() : -1;
        final int nextIndex = hasNext ? page.size() + (hasPrevious ? 1 : 0) : -1;

        String[] options = new String[
                page.size() + (hasPrevious ? 1 : 0) + (hasNext ? 1 : 0)];
        for (int i = 0; i < page.size(); i++) {
            Item item = page.get(i);
            if (item.quantity() > 1) {
                // Avoid '+' concatenation in the injection payload. R8 may
                // outline it into a donor-local synthetic helper such as La3;.
                options[i] = item.name()
                        .concat(" x")
                        .concat(Integer.toString(item.quantity()));
            } else {
                options[i] = item.name();
            }
        }
        if (hasPrevious) {
            options[previousIndex] = "Previous";
        }
        if (hasNext) {
            options[nextIndex] = "Next";
        }

        GameScene.show(new WndOptions(owner.name(), "Select an item to take", options) {
            @Override
            protected void onSelect(int index) {
                super.onSelect(index);

                if (index >= 0 && index < page.size()) {
                    takeItem(hero, page.get(index));
                    if (!stored.isEmpty()) {
                        showTakeSelector(owner, hero, start);
                    }
                } else if (hasPrevious && index == previousIndex) {
                    showTakeSelector(owner, hero, start - TAKE_PAGE_SIZE);
                } else if (hasNext && index == nextIndex) {
                    showTakeSelector(owner, hero, end);
                }
            }
        });
    }

    private boolean canStore(Item owner, Item item) {
        return item != null
                && owner != null
                && item.getClass() != owner.getClass();
    }

    private boolean putSingle(Item owner, Hero hero, Item item) {
        if (hero == null || hero.belongings == null || hero.belongings.backpack == null
                || !canStore(owner, item)) {
            return false;
        }

        if (item.isEquipped(hero)) {
            if (!(item instanceof EquipableItem)
                    || !((EquipableItem) item).doUnequip(hero, false)) {
                GLog.w("Can't unequip selected item.");
                return false;
            }
        }

        Item detached = item.detachAll(hero.belongings.backpack);
        if (detached == null || detached.quantity() <= 0) {
            return false;
        }

        absorb(detached);
        GLog.i("Stored item in the ankh.");
        Sample.INSTANCE.play(Assets.Sounds.ITEM);
        Item.updateQuickslot();
        return true;
    }

    private boolean takeItem(Hero hero, Item item) {
        if (hero == null || hero.belongings == null || hero.belongings.backpack == null
                || item == null || !stored.contains(item)) {
            return false;
        }

        if (!item.collect(hero.belongings.backpack)) {
            GLog.w("Backpack full; item remains stored.");
            return false;
        }

        stored.remove(item);
        if (stored.isEmpty()) {
            takePageStart = 0;
        }
        GLog.i("Took item from the ankh.");
        Sample.INSTANCE.play(Assets.Sounds.ITEM);
        Item.updateQuickslot();
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
}
