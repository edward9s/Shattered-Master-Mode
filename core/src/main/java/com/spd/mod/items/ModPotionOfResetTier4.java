package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.ExoticPotion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfDivineInspiration;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

import java.util.Map;

public class ModPotionOfResetTier4 extends ExoticPotion {

    public ModPotionOfResetTier4() {
        super();
        this.icon = ItemSpriteSheet.SCROLL_DIVINATE;
        this.unique = true;
    }

    @Override
    public void reset() {
        this.image = new PotionOfDivineInspiration().image;
        this.color = "exotic_tier4";
    }

    @Override
    public String name() {
        return "Exotic Potion of Tier 4 Reset";
    }

    @Override
    public String info() {
        return desc();
    }

    @Override
    public String desc() {
        return "Drinking this exotic brew will magically reset all your Tier 4 talents, returning the spent points.";
    }

    @Override
    public void apply(Hero hero) {
        identify();

        @SuppressWarnings("unchecked")
        Map<Integer, Integer> tier4 = (Map<Integer, Integer>) hero.talents.get(3);
        for (Map.Entry<Integer, Integer> entry : tier4.entrySet()) {
            entry.setValue(0);
        }

        hero.updateHT(true);
        Dungeon.saveAll();

        GLog.h("Tier 4 Reset!");
    }

    @Override
    public int value() {
        return 50;
    }

    @Override
    public int energyVal() {
        return 10;
    }

    @Override
    public boolean isIdentified() {
        return true;
    }

    @Override
    public boolean isKnown() {
        return true;
    }
}
