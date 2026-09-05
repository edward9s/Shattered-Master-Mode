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
        switch (tier) {
            case 1:
                this.color = "tier1_reset";
                break;
            case 2:
                this.color = "tier2_reset";
                break;
            case 3:
                this.color = "exotic_tier3";
                break;
            case 4:
                this.color = "exotic_tier4";
                break;
            default:
                this.color = "tier_reset";
                break;
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
        switch (tier) {
            case 1: return "Potion of Tier 1 Reset";
            case 2: return "Potion of Tier 2 Reset";
            case 3: return "Exotic Potion of Tier 3 Reset";
            case 4: return "Exotic Potion of Tier 4 Reset";
            default: return "Potion of Tier Reset";
        }
    }

    @Override
    public String desc() {
        switch (tier) {
            case 1:
                return "Drinking this potion will reset all your Tier 1 talents, returning the spent points.";
            case 2:
                return "Drinking this potion will reset all your Tier 2 talents, returning the spent points.";
            case 3:
                return "Drinking this exotic brew will magically reset all your Tier 3 talents, returning the spent points.";
            case 4:
                return "Drinking this exotic brew will magically reset all your Tier 4 talents, returning the spent points.";
            default:
                return "Drinking this potion will reset talents, returning the spent points.";
        }
    }

    private void logResetFailure() {
        switch (tier) {
            case 1: GLog.w("Reset Tier 1 failed!"); break;
            case 2: GLog.w("Reset Tier 2 failed!"); break;
            case 3: GLog.w("Reset Tier 3 failed!"); break;
            case 4: GLog.w("Reset Tier 4 failed!"); break;
            default: GLog.w("Reset Tier failed!"); break;
        }
    }

    private void logResetSuccess() {
        switch (tier) {
            case 1: GLog.h("Tier 1 Reset!"); break;
            case 2: GLog.h("Tier 2 Reset!"); break;
            case 3: GLog.h("Tier 3 Reset!"); break;
            case 4: GLog.h("Tier 4 Reset!"); break;
            default: GLog.h("Tier Reset!"); break;
        }
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
            logResetFailure();
        }
        logResetSuccess();
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
