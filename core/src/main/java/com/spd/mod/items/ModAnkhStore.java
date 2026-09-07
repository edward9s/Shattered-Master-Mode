package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;
import com.spd.mod.mechanics.ModItemCompat;
import com.spd.mod.mechanics.ModLootStorage;
import com.watabou.utils.Bundle;

/**
 * Thin ModAnkh adapter over the shared Loot storage implementation.
 *
 * Storage, Loot behavior, stack merging, sorting, save/restore, and Take semantics all live in
 * {@link ModLootStorage}. This class only keeps the ModAnkh-specific owner level display and the
 * direct Put/Take action entry points expected by {@link ModAnkh}.
 */
public final class ModAnkhStore extends ModLootStorage {

    private transient Item owner;

    public void bindOwner(Item owner) {
        this.owner = owner;

        // Keep an explicit anonymous class rather than a lambda so R8 cannot outline the listener
        // into an unrelated donor-global synthetic class.
        setChangeListener(new Runnable() {
            @Override
            public void run() {
                syncLevel();
            }
        });
    }

    public void syncLevel() {
        ModItemCompat.setLevel(owner, size());
    }

    /** Kept as an explicit method because ModAnkh's injected ABI already calls this class directly. */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Explicit forwarding methods keep ModAnkh's existing call surface stable while all behavior
     * remains implemented by ModLootStorage.
     */
    @Override
    public int size() {
        return super.size();
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
    }

    public void loot(Item owner, Hero hero) {
        if (this.owner != owner) {
            bindOwner(owner);
        }
        super.loot(hero);
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
                return ModLootStorage.canStore(item);
            }

            @Override
            public void onSelect(Item item) {
                if (item != null && putSingle(hero, item)) {
                    showPutSelector(owner, hero);
                }
            }
        });
    }

    public void showTakeSelector(Item owner, Hero hero) {
        if (owner == null || hero == null || size() <= 0) {
            return;
        }
        GameScene.show(new WndModLoot(this, owner.name(), WndModLoot.Mode.TAKE));
    }
}
