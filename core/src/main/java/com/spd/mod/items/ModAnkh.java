package com.spd.mod.items;

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

    public static final String AC_UNBLESS = "UNBLESS";

    // Times revived via blessed ankh (kept inventory, instant revive).
    private int timesRevived = 0;
    // Times resurrected via unblessed ankh (lost inventory, via WndResurrect).
    private int timesResurrected = 0;

    private static final String TIMES_REVIVED     = "times_revived";
    private static final String TIMES_RESURRECTED = "times_resurrected";

    public ModAnkh() {
        super();
        this.level(1);
        this.keptThoughLostInvent = true;
        this.unique = true;
    }

    @Override
    public boolean keptThroughLostInventory() {
        return true;
    }

    @Override
    public void reset() {
        super.reset();
        this.keptThoughLostInvent = true;
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(TIMES_REVIVED,     timesRevived);
        bundle.put(TIMES_RESURRECTED, timesResurrected);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        this.level(0);
        super.restoreFromBundle(bundle);
        this.keptThoughLostInvent = true;
        timesRevived     = bundle.getInt(TIMES_REVIVED);
        timesResurrected = bundle.getInt(TIMES_RESURRECTED);
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

        return actions;
    }

    @Override
    public String actionName(String action, Hero hero) {
        if (AC_UNBLESS.equals(action)) {
            return "Unbless";
        }
        return super.actionName(action, hero);
    }

    @Override
    public void execute(Hero hero, String action) {
        if (AC_BLESS.equals(action)) {
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
     * Appends revival/resurrection history to the standard description.
     * Only lines whose count > 0 are shown; at most two extra lines are added.
     *
     * "Revived"      = blessed ankh path: instant revive, inventory kept.
     * "Resurrected"  = unblessed ankh path: WndResurrect, inventory lost.
     */
    @Override
    public String desc() {
        String base = super.desc();

        StringBuilder sb = new StringBuilder(base);

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
