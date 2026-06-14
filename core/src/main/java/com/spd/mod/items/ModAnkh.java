package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.CellEmitter;
import com.shatteredpixel.shatteredpixeldungeon.effects.Speck;
import com.shatteredpixel.shatteredpixeldungeon.items.Ankh;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Bundle;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;

public class ModAnkh extends Ankh {

    public static final String AC_UNBLESS = "UNBLESS";

    public ModAnkh() {
        super();
        this.level(1);
        this.keptThoughLostInvent = true;
        this.unique = true;
    }

    @Override
    public void reset() {
        super.reset();
        this.keptThoughLostInvent = true;
        this.unique = true;
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        this.level(0);
        super.restoreFromBundle(bundle);
        this.keptThoughLostInvent = true;
        this.unique = true;
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

    private void removeBlessing() {
        try {
            Field field = Ankh.class.getDeclaredField("blessed");
            field.setAccessible(true);
            field.set(this, false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDetach() {
        super.onDetach();
        
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String className = element.getClassName();
            String methodName = element.getMethodName();
            
            if (("die".equals(methodName) && className.endsWith("Hero")) || 
                className.endsWith("WndResurrect")) {
                if (Dungeon.hero != null && Dungeon.hero.belongings != null && Dungeon.hero.belongings.backpack != null) {
                    Bag backpack = Dungeon.hero.belongings.backpack;
                    if (!backpack.items.contains(this)) {
                        backpack.items.add(this);
                        Collections.sort(backpack.items, Item.itemComparator);
                        Item.updateQuickslot();
                    }
                }
                break;
            }
        }
    }
}
