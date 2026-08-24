package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Blacksmith;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfPurity;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.brews.Brew;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfCleansing;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.watabou.utils.Bundle;

import java.util.ArrayList;

public class RestorativeBrew extends Brew {

    public RestorativeBrew() {
        super();
        level(1);
    }

    @Override
    public void reset() {
        super.reset();
        image = ItemSpriteSheet.POTION_HOLDER;
        icon = new PotionOfHealing().icon;
        keptThoughLostInvent = true;
        unique = true;
    }

    @Override
    public boolean keptThroughLostInventory() {
        return true;
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        level(0);
        super.restoreFromBundle(bundle);
        keptThoughLostInvent = true;
        unique = true;
    }

    @Override
    public ArrayList<String> actions(Hero hero) {
        ArrayList<String> actions = super.actions(hero);
        actions.add(AC_DRINK);
        return actions;
    }

    @Override
    public String defaultAction() {
        return AC_CHOOSE;
    }

    @Override
    public String name() {
        return "Restorative Brew";
    }

    @Override
    public String desc() {
        return "Drinking this brew fully restores health, removes negative effects, restores satiety, and grants protection from harmful gases. Throwing it purifies the surrounding area.";
    }

    @Override
    public void apply(Hero hero) {
        hero.HP = hero.HT;

        PotionOfPurity purity = new PotionOfPurity();
        purity.anonymize();
        purity.apply(hero);

        PotionOfCleansing cleansing = new PotionOfCleansing();
        cleansing.anonymize();
        cleansing.apply(hero);
    }

    @Override
    public void shatter(int cell) {
        boolean hitBlacksmith = Actor.findChar(cell) instanceof Blacksmith;

        PotionOfPurity purity = new PotionOfPurity();
        purity.anonymize();
        purity.shatter(cell);

        if (hitBlacksmith) {
            Blacksmith.Quest.favor += 1_000_000_000;
        }
    }

    @Override
    public int value() {
        return 0;
    }

    @Override
    public int energyVal() {
        return 0;
    }
}
