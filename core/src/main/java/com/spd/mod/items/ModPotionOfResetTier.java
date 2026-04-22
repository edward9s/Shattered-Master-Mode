package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfExperience;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.ExoticPotion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfDivineInspiration;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.utils.Bundle;
import java.util.Map;

public abstract class ModPotionOfResetTier extends ExoticPotion {

    protected int tier;
    private static final String TIER = "tier";

    // 靜態內部類別：供引擎直接反射
    public static class Tier1 extends ModPotionOfResetTier { public Tier1() { super(1); } }
    public static class Tier2 extends ModPotionOfResetTier { public Tier2() { super(2); } }
    public static class Tier3 extends ModPotionOfResetTier { public Tier3() { super(3); } }
    public static class Tier4 extends ModPotionOfResetTier { public Tier4() { super(4); } }

    // 唯一建構子：private 確保外部只能透過內部類別實體化
    private ModPotionOfResetTier(int tier) {
        this.tier = tier;
        reset();
    }

    @Override
    public void reset() {
        // 防止初始化的極端情況
        if (tier < 1) return; 

        this.unique = true;
        this.icon = (tier % 2 != 0) ? 
            ItemSpriteSheet.Icons.SCROLL_IDENTIFY : 
            ItemSpriteSheet.Icons.SCROLL_DIVINATE;

        if (tier <= 2) {
            this.image = new PotionOfExperience().image;
            this.color = "tier" + tier + "_reset";
        } else {
            this.image = new PotionOfDivineInspiration().image;
            this.color = "exotic_tier" + tier;
        }
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(TIER, tier);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        this.tier = bundle.getInt(TIER);
        reset();
    }

    @Override
    public boolean isSimilar(Item item) {
        return item.getClass() == this.getClass();
    }

    @Override
    public String name() {
        String prefix = (tier <= 2) ? "" : "Exotic ";
        return prefix + "Potion of Tier " + tier + " Reset";
    }

    @Override
    public String desc() {
        String msg = (tier <= 2) ? 
            "Drinking this potion will reset all your Tier " : 
            "Drinking this exotic brew will magically reset all your Tier ";
        return msg + tier + " talents, returning the spent points.";
    }

    @Override
    public void apply(Hero hero) {
        identify();
        Map talentsMap = (Map) hero.talents.get(tier - 1); 
        for (Object entryObj : talentsMap.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObj;
            entry.setValue(0);
        }
        hero.updateHT(true);
        try {
            Dungeon.saveAll();
        } catch (Exception e) {
            GLog.w("Reset Tier " + tier + " failed!");
        }
        GLog.h("Tier " + tier + " Reset!");
    }

    @Override
    public int value() { return (tier <= 2) ? 20 : 50; }
    @Override
    public int energyVal() { return (tier <= 2) ? 2 : 10; }
    @Override
    public boolean isIdentified() { return true; }
    @Override
    public boolean isKnown() { return true; }
}
