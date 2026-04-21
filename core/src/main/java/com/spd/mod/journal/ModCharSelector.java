package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FlavourBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Healing;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.elixirs.ElixirOfMight;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndGame;
import com.watabou.utils.Callback;
import com.watabou.utils.Reflection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.spd.mod.tools.ModToolsWindow;

public class ModCharSelector extends CellSelector.Listener implements Callback {

    private Class<? extends Buff> buffClass;
    private boolean reselecting;
    private boolean isClosing;

    public ModCharSelector(Class<? extends Buff> buffClass) {
        super();
        this.buffClass = buffClass;
    }

    public static void start(Class<? extends Buff> buffClass) {
        if (ModJournalWindow.instance != null) {
            ModJournalWindow.instance.hide();
        }
        if (ModToolsWindow.instance != null) {
            ModToolsWindow.instance.hide();
        }
        if (WndGame.instance != null) {
            WndGame.instance.hide();
        }

        GameScene.selectCell(new ModCharSelector(buffClass));
    }

    @Override
    public String prompt() {
        String name;
        try {
            Object instance = Reflection.newInstance(buffClass);
            Method m = instance.getClass().getMethod("name");
            name = (String) m.invoke(instance);
        } catch (Exception e) {
            name = buffClass.getSimpleName();
        }
        return "Toggle " + name;
    }

    @Override
    public void call() {
        GameScene.selectCell(this);
    }

    @Override
    public void onSelect(Integer pos) {
        if (pos == null) {
            if (reselecting) {
                reselecting = false;
            } else if (!isClosing) {
                isClosing = true;
                GameScene.show(new ModJournalWindow());
            }
            return;
        }

        reselecting = true;
        Char target = Actor.findChar(pos);

        if (target == null) {
            ShatteredPixelDungeon.runOnRenderThread(this);
            return;
        }

        Buff buff = target.buff(buffClass);
        String format;
        Buff resultBuff;

        if (buff != null) {
            buff.detach();
            resultBuff = buff;
            format = "Detach %s";
        } else {
            resultBuff = Buff.affect(target, buffClass);
            smartSetDuration(resultBuff, 1000000f);
            format = "Affect %s";
        }

        String displayName;
        try {
            Method m = resultBuff.getClass().getMethod("name");
            displayName = (String) m.invoke(resultBuff);
        } catch (Exception e) {
            displayName = buffClass.getSimpleName();
        }

        GLog.p(format, displayName);
        ShatteredPixelDungeon.runOnRenderThread(this);
    }

    public static void smartSetDuration(Buff buff, float duration) {
        // 策略 0: 特例處理
        if (buff instanceof Healing) {
            Healing h = (Healing) buff;
            int amount = (h.target == null) ? 10 : (int) (h.target.HT * 0.8f + 14);
            h.setHeal(amount, 0.25f, 0);
            return;
        }

        if (buff instanceof ElixirOfMight.HTBoost) {
            ((ElixirOfMight.HTBoost) buff).reset();
            return;
        }

        if (buff instanceof Sungrass.Health) {
            Sungrass.Health sh = (Sungrass.Health) buff;
            int amount = (sh.target == null) ? 10 : sh.target.HT;
            sh.boost(amount);
            return;
        }

        if (buff instanceof Bleeding) {
            ((Bleeding) buff).set(30f);
            return;
        }

        if (buff instanceof Poison) {
            ((Poison) buff).set(30f);
            return;
        }

        // 策略 1: extend(float)
        try {
            Method m = buff.getClass().getMethod("extend", float.class);
            m.invoke(buff, duration);
            return;
        } catch (Exception ignore) {}

        // 策略 2: set(float)
        try {
            Method m = buff.getClass().getMethod("set", float.class);
            m.invoke(buff, duration);
            return;
        } catch (Exception ignore) {}

        // 策略 3: reset(int)
        try {
            Method m = buff.getClass().getMethod("reset", int.class);
            m.invoke(buff, (int) duration);
            return;
        } catch (Exception ignore) {}

        // 策略 4: postpone(float)
        if (buff instanceof FlavourBuff) {
            try {
                Method m = Actor.class.getDeclaredMethod("postpone", float.class);
                m.setAccessible(true);
                m.invoke(buff, duration);
                return;
            } catch (Exception ignore) {}
        }

        // 策略 5: 修改 left 欄位
        try {
            Field f = buff.getClass().getDeclaredField("left");
            f.setAccessible(true);
            f.setFloat(buff, duration);
        } catch (Exception ignore) {}
    }
}
