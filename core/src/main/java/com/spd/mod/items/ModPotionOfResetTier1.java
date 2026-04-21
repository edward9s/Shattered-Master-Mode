package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfExperience;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

import java.util.Map;

public class ModPotionOfResetTier1 extends Potion {

    public ModPotionOfResetTier1() {
        super();
        this.icon = ItemSpriteSheet.SCROLL_IDENTIFY;
        this.unique = true;
    }

    @Override
    public void reset() {
        this.image = new PotionOfExperience().image;
        this.color = "tier1_reset";
    }

    @Override
    public String name() {
        return "Potion of Tier 1 Reset";
    }

    @Override
    public String info() {
        return desc();
    }

    @Override
    public String desc() {
        return "Drinking this potion will reset all your Tier 1 talents, returning the spent points.";
    }

    @Override
    public void apply(Hero hero) {
        identify();

        @SuppressWarnings("unchecked")
        Map<Integer, Integer> tier1 = (Map<Integer, Integer>) hero.talents.get(0);
        for (Map.Entry<Integer, Integer> entry : tier1.entrySet()) {
            entry.setValue(0);
        }

        hero.updateHT(true);
        Dungeon.saveAll();

        GLog.h("Tier 1 Reset!");
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
