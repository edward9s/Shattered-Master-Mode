package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfStrength;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

public class ModPotionOfWeakness extends Potion {

    public ModPotionOfWeakness() {
        super();
        this.icon = ItemSpriteSheet.SCROLL_TERROR;
        this.unique = true;
    }

    @Override
    public void reset() {
        this.image = new PotionOfStrength().image;
        this.color = "weakness_potion";
    }

    @Override
    public String name() {
        return "Potion of Weakness";
    }

    @Override
    public String info() {
        return desc();
    }

    @Override
    public String desc() {
        return "Drinking this potion will permanently decrease your strength by 1.";
    }

    @Override
    public void apply(Hero hero) {
        identify();

        hero.STR -= 1;

        GLog.n("You feel weaker...");
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
