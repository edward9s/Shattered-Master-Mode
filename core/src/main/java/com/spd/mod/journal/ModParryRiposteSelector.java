package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.spd.mod.mechanics.ModParryRiposte;
import com.spd.mod.tools.ModToolsWindow;
import com.watabou.utils.Callback;

public class ModParryRiposteSelector extends CellSelector.Listener implements Callback {

    private final ModParryRiposte.Mode mode;
    private final boolean remove;
    private boolean reselecting;
    private boolean isClosing;

    private ModParryRiposteSelector(ModParryRiposte.Mode mode, boolean remove) {
        this.mode = mode;
        this.remove = remove;
    }

    public static void start(ModParryRiposte.Mode mode) {
        begin(new ModParryRiposteSelector(mode, false));
    }

    public static void startRemove() {
        begin(new ModParryRiposteSelector(null, true));
    }

    private static void begin(ModParryRiposteSelector selector) {
        if (ModJournalWindow.instance != null) {
            ModJournalWindow.instance.hide();
        }
        if (ModToolsWindow.instance != null) {
            ModToolsWindow.instance.hide();
        }

        GameScene.selectCell(selector);
    }

    @Override
    public String prompt() {
        if (remove) {
            return "Remove Mod Parry / Riposte";
        }
        return mode == ModParryRiposte.Mode.PARRY
                ? "Apply Mod Parry"
                : "Apply Mod Riposte";
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

        ModParryRiposte existing = target.buff(ModParryRiposte.class);

        if (remove) {
            if (existing != null) {
                String name = existing.name();
                existing.detach();
                GLog.p("Detach %s", name);
            } else {
                GLog.w("No Mod Parry / Riposte buff on target");
            }
        } else if (existing != null) {
            if (existing.mode() != mode) {
                existing.setMode(mode);
                GLog.p("Switch to %s", existing.name());
            } else {
                GLog.p("%s already active", existing.name());
            }
        } else {
            ModParryRiposte created = ModParryRiposte.apply(target, mode);
            if (created != null) {
                GLog.p("Affect %s", created.name());
            } else {
                GLog.w("Unable to apply Mod Parry / Riposte");
            }
        }

        ShatteredPixelDungeon.runOnRenderThread(this);
    }
}
