package com.spd.mod.items;

import com.spd.mod.mechanics.ModDebug;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.CellEmitter;
import com.shatteredpixel.shatteredpixeldungeon.effects.Speck;
import com.shatteredpixel.shatteredpixeldungeon.items.Ankh;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndUseItem;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Bundle;

import java.lang.reflect.Field;
import java.util.ArrayList;

public class ModAnkh extends Ankh {

    public static final String AC_CHOOSE = "CHOOSE";
    public static final String AC_UNBLESS = "UNBLESS";
    public static final String AC_CONSOLE = "CONSOLE";
    public static final String AC_PUT = "PUT";
    public static final String AC_TAKE = "TAKE";

    // Times revived via blessed ankh (kept inventory, instant revive).
    private int timesRevived = 0;
    // Times resurrected via unblessed ankh (lost inventory, via WndResurrect).
    private int timesResurrected = 0;

    private static final String TIMES_REVIVED     = "times_revived";
    private static final String TIMES_RESURRECTED = "times_resurrected";

    private final ModAnkhStore store = new ModAnkhStore();

    public ModAnkh() {
        super();
        reset();
    }

    @Override
    public boolean keptThroughLostInventory() {
        return true;
    }

    @Override
    public void reset() {
        super.reset();
        this.icon = ItemSpriteSheet.Icons.POTION_EXP;
        this.keptThoughLostInvent = true;
        this.unique = true;
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(TIMES_REVIVED,     timesRevived);
        bundle.put(TIMES_RESURRECTED, timesResurrected);
        store.storeInBundle(bundle);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        timesRevived     = bundle.getInt(TIMES_REVIVED);
        timesResurrected = bundle.getInt(TIMES_RESURRECTED);
        store.restoreFromBundle(bundle);
        reset();
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

                if (Dungeon.hero != null && Dungeon.hero.belongings != null
                        && Dungeon.hero.belongings.backpack != null) {
                    Bag backpack = Dungeon.hero.belongings.backpack;
                    if (!backpack.contains(this)) {
                        this.collect(backpack);
                    }
                }
                break;

            } else if (className.endsWith("WndResurrect")) {
                // Detached from WndResurrect: unblessed path = resurrection, losing inventory.
                timesResurrected++;

                if (Dungeon.hero != null && Dungeon.hero.belongings != null
                        && Dungeon.hero.belongings.backpack != null) {
                    Bag backpack = Dungeon.hero.belongings.backpack;
                    if (!backpack.contains(this)) {
                        this.collect(backpack);
                    }
                }
                break;
            }
        }
    }

    @Override
    public String defaultAction() {
        return AC_CHOOSE;
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

        if (!actions.contains(AC_PUT)) {
            actions.add(AC_PUT);
        }
        if (!store.isEmpty() && !actions.contains(AC_TAKE)) {
            actions.add(AC_TAKE);
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
        } else if (AC_PUT.equals(action)) {
            return "Put";
        } else if (AC_TAKE.equals(action)) {
            // Avoid '+' concatenation here: R8 may outline it into a donor-local
            // helper class, which is outside the standalone ModAnkh payload.
            return "Take (".concat(Integer.toString(store.size())).concat(")");
        } else if (AC_CONSOLE.equals(action)) {
            return "Console";
        }
        return super.actionName(action, hero);
    }

    @Override
    public void execute(Hero hero, String action) {
        if (AC_CHOOSE.equals(action)) {
            GameScene.show(new WndUseItem(null, this));
        } else if (AC_CONSOLE.equals(action)) {
            GameScene.cancel();
            ModDebug.open();
        } else if (AC_PUT.equals(action)) {
            GameScene.cancel();
            store.showPutSelector(this, hero);
        } else if (AC_TAKE.equals(action)) {
            GameScene.cancel();
            store.showTakeSelector(this, hero);
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

    /**
     * Appends revival/resurrection history and stored-item count to the standard description.
     *
     * "Revived"      = blessed ankh path: instant revive, inventory kept.
     * "Resurrected"  = unblessed ankh path: WndResurrect, inventory lost.
     */
    @Override
    public String desc() {
        String base = super.desc();
        StringBuilder sb = new StringBuilder(base);

        if (!store.isEmpty()) {
            sb.append("\n\nCurrently storing ")
              .append(store.size())
              .append(store.size() == 1 ? " item." : " items.");
        }

        if (false && timesRevived > 0) {
            sb.append("\n\nThis ankh has revived you ")
              .append(timesRevived)
              .append(timesRevived == 1 ? " time" : " times")
              .append(" on the spot, with your inventory still in hand.");
        }

        if (false && timesResurrected > 0) {
            sb.append("\n\nThis ankh has resurrected you ")
              .append(timesResurrected)
              .append(timesResurrected == 1 ? " time" : " times")
              .append(", scattering your inventory at the place you fell.");
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
