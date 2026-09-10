package com.spd.mod.items;

import com.spd.mod.mechanics.ModDebug$Console;
import com.spd.mod.mechanics.ModItemCompat;
import com.spd.mod.mechanics.ModLegacyCompat;
import com.spd.mod.mechanics.ModLootStorage;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.CellEmitter;
import com.shatteredpixel.shatteredpixeldungeon.effects.Speck;
import com.shatteredpixel.shatteredpixeldungeon.items.Ankh;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Bundle;

import java.lang.reflect.Field;
import java.util.ArrayList;

public class ModAnkh extends Ankh {

    public static final String AC_STORE = "STORE";
    public static final String AC_UNBLESS = "UNBLESS";
    public static final String AC_CONSOLE = "CONSOLE";
    public static final String AC_LOOT = "LOOT";

    // Times revived via blessed ankh (kept inventory, instant revive).
    private int timesRevived = 0;
    // Times resurrected via unblessed ankh (lost inventory, via WndResurrect).
    private int timesResurrected = 0;

    // Item.detach() is final and clears the quickslot before onDetach(). Hero.die()
    // checks isBlessed() immediately before consuming the selected ankh, so keep the
    // current slot here long enough to restore it after our infinite-use collect.
    // -1 means the ModAnkh was not quickslotted and must remain unassigned.
    private int revivalQuickslot = -1;

    private static final String TIMES_REVIVED     = "times_revived";
    private static final String TIMES_RESURRECTED = "times_resurrected";

    private final ModLootStorage storage = new ModLootStorage();

    public ModAnkh() {
        super();
        reset();
        bindStorage();
    }

    private void bindStorage() {
        storage.setChangeListener(new Runnable() {
            @Override
            public void run() {
                syncCount();
            }
        });
    }

    private void syncCount() {
        ModItemCompat.setLevel(this, storage.size());
    }

    @Override
    public boolean keptThroughLostInventory() {
        return true;
    }

    @Override
    public void reset() {
        super.reset();
        this.icon = ModLegacyCompat.itemIcon("POTION_EXP", -1);
        // Older SPD UIs filter quickslot candidates and highlight actions by the
        // Item.defaultAction field directly instead of calling defaultAction().
        // Keep both contracts synchronized so injected ModAnkh works on either ABI.
        this.defaultAction = AC_STORE;
        this.keptThoughLostInvent = true;
        this.unique = true;
        this.revivalQuickslot = -1;
    }

    @Override
    public boolean isBlessed() {
        if (Dungeon.quickslot != null) {
            revivalQuickslot = Dungeon.quickslot.getSlot(this);
        } else {
            revivalQuickslot = -1;
        }
        return super.isBlessed();
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(TIMES_REVIVED,     timesRevived);
        bundle.put(TIMES_RESURRECTED, timesResurrected);
        // Keep the historical flat ModAnkh storage keys for save compatibility.
        storage.storeInBundle(bundle);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        timesRevived     = bundle.getInt(TIMES_REVIVED);
        timesResurrected = bundle.getInt(TIMES_RESURRECTED);
        storage.restoreFromBundle(bundle);
        bindStorage();
        reset();
        syncCount();
    }

    @Override
    protected void onDetach() {
        super.onDetach();

        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String className  = element.getClassName();
            String methodName = element.getMethodName();

            if ("die".equals(methodName) && className.endsWith("Hero")) {
                // Detached from die(): blessed path = instant revive, keeping inventory.
                timesRevived++;
                restoreAfterRevival();
                break;

            } else if (className.endsWith("WndResurrect")) {
                // Detached from WndResurrect: unblessed path = resurrection, losing inventory.
                timesResurrected++;
                restoreAfterRevival();
                break;
            }
        }
    }

    private void restoreAfterRevival() {
        if (Dungeon.hero != null && Dungeon.hero.belongings != null
                && Dungeon.hero.belongings.backpack != null) {
            Bag backpack = Dungeon.hero.belongings.backpack;
            if (!backpack.contains(this)) {
                this.collect(backpack);
            }

            if (revivalQuickslot >= 0 && Dungeon.quickslot != null
                    && backpack.contains(this)) {
                Dungeon.quickslot.setSlot(revivalQuickslot, this);
                updateQuickslot();
            }
        }
        revivalQuickslot = -1;
    }

    @Override
    public String defaultAction() {
        return AC_STORE;
    }

    @Override
    public ArrayList<String> actions(Hero hero) {
        ArrayList<String> actions = super.actions(hero);

        if (isBlessed()) {
            actions.remove(AC_BLESS);
            if (!actions.contains(AC_UNBLESS)) {
                actions.add(AC_UNBLESS);
            }
        } else {
            actions.remove(AC_UNBLESS);
            if (!actions.contains(AC_BLESS)) {
                actions.add(AC_BLESS);
            }
        }

        if (!actions.contains(AC_STORE)) {
            actions.add(AC_STORE);
        }
        if (!actions.contains(AC_LOOT)) {
            actions.add(AC_LOOT);
        }
        if (!actions.contains(AC_CONSOLE)) {
            actions.add(AC_CONSOLE);
        }

        return actions;
    }

    @Override
    public String actionName(String action, Hero hero) {
        if (AC_UNBLESS.equals(action)) {
            return "Unbless";
        } else if (AC_STORE.equals(action)) {
            return "Store";
        } else if (AC_LOOT.equals(action)) {
            return "Loot";
        } else if (AC_CONSOLE.equals(action)) {
            return "Console";
        }
        return super.actionName(action, hero);
    }

    @Override
    public void execute(Hero hero, String action) {
        if (AC_STORE.equals(action)) {
            storage.reclaimPending(hero);
            GameScene.show(new WndModLoot(storage, name(), WndModLoot.Mode.USE));
        } else if (AC_CONSOLE.equals(action)) {
            GameScene.cancel();
            ModDebug$Console.open();
        } else if (AC_LOOT.equals(action)) {
            GameScene.cancel();
            storage.loot(hero);
        } else if (AC_BLESS.equals(action)) {
            GameScene.cancel();
            setCurrent(hero);

            if (!isBlessed()) {
                bless();
                GLog.p(Messages.get(this, "bless"));
                hero.busy();
                Sample.INSTANCE.play(Assets.Sounds.EVOKE);
                CellEmitter.get(hero.pos).start(Speck.factory(Speck.LIGHT), 0.2f, 3);
                hero.sprite.operate(hero.pos);
            }
        } else if (AC_UNBLESS.equals(action)) {
            GameScene.cancel();
            setCurrent(hero);

            if (isBlessed()) {
                removeBlessing();
                GLog.w("The ankh is no longer blessed.");
                hero.busy();
                Sample.INSTANCE.play(Assets.Sounds.SHATTER);
                hero.sprite.operate(hero.pos);
            }
        } else {
            super.execute(hero, action);
        }
    }

    @Override
    public String desc() {
        StringBuilder sb = new StringBuilder(super.desc());
        sb.append("\n\nReusable. Store opens shared storage; Loot gathers reachable level items and stores overflow.");
        if (storage.size() > 0) {
            sb.append(" Stored: ").append(storage.size()).append(".");
        }
        return sb.toString();
    }

    private void removeBlessing() {
        try {
            Field field = Ankh.class.getDeclaredField("blessed");
            field.setAccessible(true);
            field.set(this, false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
