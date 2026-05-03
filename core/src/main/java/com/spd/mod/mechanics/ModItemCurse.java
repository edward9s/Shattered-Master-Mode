package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;
import com.watabou.noosa.audio.Sample;

public class ModItemCurse {

    public ModItemCurse() {
    }

    public static void openSelector() {
        GameScene.selectItem(new WndBag.ItemSelector() {
            @Override
            public String textPrompt() {
                return ModItemCurse.textPrompt();
            }
            @Override
            public boolean itemSelectable(Item item) {
                return ModItemCurse.itemSelectable(item);
            }
            @Override
            public void onSelect(Item item) {
                ModItemCurse.onSelect(item);
            }
        });
    }

    public static String textPrompt() {
        return "Curse/Cleanse";
    }

    public static boolean itemSelectable(Item item) {
        return true;
    }

    public static void onSelect(Item item) {
        if (item == null) {
            return;
        }

        if (item.cursed) {
            item.cursed = false;
            GLog.p("Cleansed %s", new Object[]{item.name()});
        } else {
            item.cursed = true;
            GLog.n("Cursed %s", new Object[]{item.name()});
        }
        
        item.cursedKnown = true;
        Item.updateQuickslot();

        Sample.INSTANCE.play(Assets.Sounds.READ);

        openSelector();
    }
}
