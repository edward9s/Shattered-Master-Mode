package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.ExoticPotion;
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
        this.level(tier);
        reset();
    }

    /**
     * Keep dynamic tier strings inside this kept class. Java string-concat indy can otherwise be
     * desugared/merged by R8 into a donor-global synthetic helper that is not a stable injection ABI.
     */
    private static String tierText(String prefix, int tier, String suffix) {
        return new StringBuilder(prefix).append(tier).append(suffix).toString();
    }
    
    @Override
    public boolean keptThroughLostInventory() {
        return true;
    }

    @Override
    public void reset() {
        this.keptThoughLostInvent = true;
        this.unique = true;
        // 防止初始化的極端情況
        if (tier < 1) return; 

        this.icon = (tier % 2 != 0) ? 
            ItemSpriteSheet.Icons.SCROLL_IDENTIFY : 
            ItemSpriteSheet.Icons.SCROLL_DIVINATE;

        this.image = tier <= 2 ? ItemSpriteSheet.POTION_HOLDER : ItemSpriteSheet.ELIXIR_HOLDER;
        if (tier <= 2) {
            this.color = tierText("tier", tier, "_reset");
        } else {
            this.color = tierText("exotic_tier", tier, "");
        }
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(TIER, tier);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        this.level(0);
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
        String prefix = (tier <= 2) ? "Potion of Tier " : "Exotic Potion of Tier ";
        return tierText(prefix, tier, " Reset");
    }

    @Override
    public String desc() {
        String prefix = (tier <= 2) ?
            "Drinking this potion will reset all your Tier " :
            "Drinking this exotic brew will magically reset all your Tier ";
        return tierText(prefix, tier, " talents, returning the spent points.");
    }

    @Override
    public void apply(Hero hero) {
        identify();
        Map<?, Integer> talentsMap = (Map<?, Integer>) hero.talents.get(tier - 1); 
        for (Map.Entry<?, Integer> entry : talentsMap.entrySet()) {
            entry.setValue(0);
        }
        hero.updateHT(true);
        try {
            Dungeon.saveAll();
        } catch (Exception e) {
            GLog.w(tierText("Reset Tier ", tier, " failed!"));
        }
        GLog.h(tierText("Tier ", tier, " Reset!"));
    }
    
    @Override
	public int value() {
		return 0;
	}

    @Override
    public int energyVal() {
        // ExoticPotion.energyVal() 依賴 exoToReg 對照表，本類別不在表中會 NPE
        return 6;
    }

    @Override
    public boolean isIdentified() {
        return true;
    }

    @Override
    public boolean isKnown() {
        return true;
    }
    
    @Override
    public void setKnown() {
        // 阻斷系統註冊機制以防崩潰
    }
}
