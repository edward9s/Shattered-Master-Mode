package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.watabou.utils.Bundle;

import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.ScrollHolder;
import java.util.Collections;

import com.spd.mod.mechanics.ModAssassin;

public class ModScrollOfAssassin extends Scroll implements ModReusable {

    /**
     * 關鍵修正：快捷欄的自動瞄準 (QuickSlotButton.autoAim) 會先測
     * targetingPos(hero, target.pos) == target.pos，預設實作是投射物彈道的
     * collisionPos——當英雄與敵人之間有牆角截斷彈道時，直瞄失敗，
     * autoAim 便改回傳「彈道會停在敵人身上」的格子，也就是英雄—敵人
     * 連線延長到敵人背後的位置。該格上沒有角色，Selector 會誤入
     * 空地閃現分支：英雄被傳到敵人背後、無攻擊、無訊息。
     *
     * 本卷軸的目標選取不是投射物，直接回傳 dst 即可：
     * autoAim 的直瞄檢查恆成立，永遠把敵人本格送進 Selector，
     * 二連點快捷欄自動刺殺上次目標的便利性照舊。
     */
    @Override
    public int targetingPos(Hero user, int dst) {
        return dst;
    }

    @Override
    public boolean keptThroughLostInventory() {
        return true;
    }

    /** 拒絕被收進捲軸筒,讓 Item.collect() 的自動分袋流程改把卷軸放進主背包。 */
    @Override
    public boolean collect(Bag container) {
        if (container instanceof ScrollHolder) {
            return false;
        }
        return super.collect(container);
    }

    @Override
    public void reset() {
        super.reset();
        this.image = ItemSpriteSheet.SCROLL_HOLDER;
        this.icon = ItemSpriteSheet.Icons.RING_ACCURACY;
        this.rune = "scroll_assassin";
        this.stackable = false;
        this.keptThoughLostInvent = true;
        this.unique = true;
        this.usesTargeting = true;
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        this.level(0);
        reset();
    }

    @Override
    public String name() {
        return "Scroll of Assassin";
    }

    @Override
    public void execute(Hero hero, String action) {
        if ("READ".equals(action)) {
            doRead();
        } else {
            super.execute(hero, action);
        }
    }

    public void doRead() {
        ModAssassin.cast(Dungeon.hero);
    }

    @Override
    public String desc() {
        return "Hero will attempt to assssin an enemy or flash to anywhere.";
    }

    @Override
    public int value() {
        return 0;
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
    }

    @Override
    protected void onDetach() {
        super.onDetach();

        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String className = element.getClassName();
            String methodName = element.getMethodName();

            if (("die".equals(methodName) && className.endsWith("Hero")) ||
                className.endsWith("WndResurrect")) {
                if (Dungeon.hero != null && Dungeon.hero.belongings != null && Dungeon.hero.belongings.backpack != null) {
                    Bag backpack = Dungeon.hero.belongings.backpack;
                    if (!backpack.items.contains(this)) {
                        backpack.items.add(this);
                        Collections.sort(backpack.items, Item.itemComparator);
                        Item.updateQuickslot();
                    }
                }
                break;
            }
        }
    }
}
