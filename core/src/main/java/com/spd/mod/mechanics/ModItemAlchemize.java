package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndEnergizeItem;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTradeItem;
import com.watabou.noosa.audio.Sample;

public class ModItemAlchemize extends WndOptions {

    private Item targetItem;

    public ModItemAlchemize(Item item) {
        super(
            new ItemSprite(item),
            item.name(),
            item.info(),
            getOptions(item)
        );
        this.targetItem = item;
    }

    private static String[] getOptions(Item item) {
        int qty = item.quantity();
        int totalValue = item.value();
        int unitValue = totalValue / qty;
        int totalEnergy = item.energyVal();
        int unitEnergy = totalEnergy / qty;

        if (qty <= 1) {
            return new String[]{
                "Sell One (" + unitValue + ")",
                "Energize One (" + unitEnergy + ")"
            };
        } else {
            return new String[]{
                "Sell One (" + unitValue + ")",
                "Sell All (" + totalValue + ")",
                "Energize One (" + unitEnergy + ")",
                "Energize All (" + totalEnergy + ")"
            };
        }
    }

    public static void openSelector() {
        ModItemSelector selector = new ModItemSelector("com.spd.mod.mechanics.ModItemAlchemize");
        GameScene.selectItem(selector);
    }

    public static String textPrompt() {
        return "Select item to Alchemize";
    }

    public static boolean itemSelectable(Item item) {
        return true;
    }

    public static void onSelect(Item item) {
        if (item != null) {
            GameScene.show(new ModItemAlchemize(item));
        }
    }

    @Override
    protected void onSelect(int index) {
        int qty = this.targetItem.quantity();

        if (index == 0) {
            WndTradeItem.sellOne(this.targetItem, null);
        } else if (index == 1) {
            if (qty == 1) {
                WndEnergizeItem.energizeOne(this.targetItem);
            } else {
                WndTradeItem.sell(this.targetItem, null);
            }
        } else if (index == 2) {
            WndEnergizeItem.energizeOne(this.targetItem);
        } else if (index == 3) {
            WndEnergizeItem.energizeAll(this.targetItem);
        }

        Sample.INSTANCE.play(Assets.Sounds.TELEPORT);
        openSelector();
    }
}
