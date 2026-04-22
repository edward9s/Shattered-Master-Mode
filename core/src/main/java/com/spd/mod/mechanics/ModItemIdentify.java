package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;
import com.watabou.noosa.audio.Sample;

public class ModItemIdentify {

    public ModItemIdentify() {
    }

    public static void openSelector() {
        GameScene.selectItem(new WndBag.ItemSelector() {
            @Override
            public String textPrompt() {
                return ModItemIdentify.textPrompt();
            }
            @Override
            public boolean itemSelectable(Item item) {
                return ModItemIdentify.itemSelectable(item);
            }
            @Override
            public void onSelect(Item item) {
                ModItemIdentify.onSelect(item);
            }
        });
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

        openSelector();
    }
}
