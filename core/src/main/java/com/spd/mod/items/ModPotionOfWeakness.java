package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfStrength;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.utils.Bundle;

public class ModPotionOfWeakness extends Potion {

    public ModPotionOfWeakness() {
        super();
        this.level(1);
        this.icon = ItemSpriteSheet.Icons.SCROLL_TERROR;
        this.keptThoughLostInvent = true;
        this.unique = true;
    }
    
    @Override
    public boolean keptThroughLostInventory() {
        return true;
    }

    @Override
    public void reset() {
        this.image = new PotionOfStrength().image;
        this.color = "weakness_potion";
        this.keptThoughLostInvent = true;
        this.unique = true;
    }
    
    @Override
    public void restoreFromBundle(Bundle bundle) {
        // 在讀取存檔前將等級歸零，抵消建構子的預設值，避免 Item 原生機制的 upgrade 疊加
        this.level(0);
        super.restoreFromBundle(bundle);
        this.keptThoughLostInvent = true;
        this.unique = true;
    }

    @Override
    public String name() {
        return "Potion of Weakness";
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
