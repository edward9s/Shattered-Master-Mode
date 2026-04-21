package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.audio.Sample;

public class ModItemIdentify {

    public ModItemIdentify() {
    }

    public static String textPrompt() {
        return "Identify/Forget";
    }

    public static boolean itemSelectable(Item item) {
        return true;
    }

    public static void onSelect(Item item) {
        if (item == null) {
            return;
        }

        boolean levelKnown = item.levelKnown;
        String logMsg;

        if (!levelKnown) {
            item.identify();
            logMsg = "Item Identified: %s";
        } else {
            item.levelKnown = false;
            logMsg = "Item Forgotten: %s";
        }

        Item.updateQuickslot();

        GLog.i(logMsg, new Object[]{item.name()});

        Sample.INSTANCE.play(Assets.Sounds.READ);

        ModItemSelector selector = new ModItemSelector("com.spd.mod.mechanics.ModItemIdentify");
        GameScene.selectItem(selector);
    }
}
