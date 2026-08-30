package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.spd.mod.items.WndModLoot;
import com.spd.mod.journal.ModLootBuffOverlay;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;

/** Permanent Hero buff backed directly by shared Loot mechanics. */
public class ModLootBuff extends Buff {

    private static final String STORAGE = "storage";

    private ModLootStorage storage = new ModLootStorage();

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
    }

    @Override
    public boolean attachTo(Char target) {
        if (!(target instanceof Hero)) {
            return false;
        }
        return super.attachTo(target);
    }

    @Override
    public void fx(boolean on) {
        if (on) {
            ModLootBuffOverlay.ensureInstalled();
        }
    }

    @Override
    public boolean act() {
        ModLootBuffOverlay.ensureInstalled();
        spend(TICK);
        return true;
    }

    public ModLootStorage storage() {
        return storage;
    }

    public void open() {
        if (target instanceof Hero && target == Dungeon.hero) {
            Hero hero = (Hero) target;
            storage.reclaimLent(hero);
            GameScene.show(new WndModLoot(storage, name(), WndModLoot.Mode.USE));
        }
    }

    @Override
    public void detach() {
        if (target instanceof Hero && Dungeon.level != null) {
            Hero hero = (Hero) target;
            storage.reclaimLent(hero);
            storage.dump(hero);
        }
        super.detach();
        BuffIndicator.refreshHero();
    }

    @Override
    public int icon() {
        return BuffIndicator.AMULET;
    }

    @Override
    public void tintIcon(Image icon) {
        icon.hardlight(0xFFD45A);
    }

    @Override
    public String iconTextDisplay() {
        return "L";
    }

    @Override
    public String name() {
        return "Loot";
    }

    @Override
    public String desc() {
        return "Permanent Master Mode buff for the Hero. Tap its buff icon to open Loot / Put / Take / Dump and directly use stored items. "
                + "It uses the same shared Loot storage mechanics as Scroll of Loot. Removing the buff first returns every stored item to the Hero's bags, or drops it at the Hero's feet if the bags are full. "
                + "Long-press or right-click the buff icon to view this description.";
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(STORAGE, storage);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        Object restored = bundle.get(STORAGE);
        if (!(restored instanceof ModLootStorage)) {
            throw new IllegalStateException("Loot buff bundle has no valid Loot storage");
        }
        storage = (ModLootStorage) restored;
    }
}
