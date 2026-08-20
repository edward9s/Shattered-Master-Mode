package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.watabou.utils.Reflection;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mod 視窗共用的物品排序規則,讓卷軸內的清單在每個視窗看起來都一致。
 *
 * 先分組:Mod 自訂物品 → 魔法袋 → 藥劑筒 → 卷軸筒 → 絨布袋 → 其他,
 * 同組內再用遊戲原生的 {@link Generator.Category#order(Item)}(與背包/煉金介面同一套次序)。
 * 只影響順序,不做任何畫面分組。
 *
 * 【跨版本考量】這個 mod 要能套在各種 SPD 衍生版上,所以這裡不 import 任何特定的袋子類別,
 * 只用「類別簡名」在執行期解析。某個衍生版沒有(或改名了)其中一種袋子時,該分組會自動消失,
 * 屬於它的東西落到「其他」,其餘分組照常運作,而不是整包編不過。
 */
public class ModItemOrder {

    private static final String[] BAG_GROUPS = {
            "MagicalHolster",
            "PotionBandolier",
            "ScrollHolder",
            "VelvetPouch",
    };

    private static final int GROUP_MOD       = 0;
    private static final int GROUP_FIRST_BAG = 1;
    private static final int GROUP_OTHER     = GROUP_FIRST_BAG + BAG_GROUPS.length;

    /** 解析結果快取(含解析失敗的 null),避免每次比較都重跑一次反射。 */
    private static final Map<String, Bag> PROBES = new HashMap<>();

    public static final Comparator<Item> COMPARATOR = new Comparator<Item>() {
        @Override
        public int compare(Item lhs, Item rhs) {
            int group = group(lhs) - group(rhs);
            if (group != 0) {
                return group;
            }
            return Generator.Category.order(lhs) - Generator.Category.order(rhs);
        }
    };

    public static void sort(List<Item> items) {
        if (items != null && items.size() > 1) {
            Collections.sort(items, COMPARATOR);
        }
    }

    public static int group(Item item) {
        if (isModItem(item)) {
            return GROUP_MOD;
        }
        // Bag.canHold() 對「任何袋子」都直接回傳 true,會誤判成第一組,先擋掉
        if (item == null || item instanceof Bag) {
            return GROUP_OTHER;
        }
        for (int i = 0; i < BAG_GROUPS.length; i++) {
            Bag probe = probe(BAG_GROUPS[i]);
            if (probe != null && probe.canHold(item)) {
                return GROUP_FIRST_BAG + i;
            }
        }
        return GROUP_OTHER;
    }

    /** 本 mod 自訂的物品(com.spd.mod.* 底下的類別)。 */
    public static boolean isModItem(Item item) {
        return item != null && item.getClass().getName().startsWith("com.spd.mod.");
    }

    /**
     * 拿一個「空的」袋子實例來當分類器:分類完全交給該袋子自己的 canHold(),
     * 因此不需要在這裡複製一份「哪些東西算法杖/藥劑/種子」的型別清單,
     * 衍生版本自己改了收納規則也會跟著正確。
     *
     * 刻意不用英雄身上那一個:真實袋子裝滿時 canHold() 會回傳 false,分組會隨著背包狀態飄動。
     * 這個實例只在記憶體裡當判準用,不會被放進遊戲。
     */
    private static Bag probe(String simpleName) {
        if (PROBES.containsKey(simpleName)) {
            return PROBES.get(simpleName);
        }

        // 套件路徑從 Bag 自己推導,不寫死字串
        String bagClass = Bag.class.getName();
        String pkg = bagClass.substring(0, bagClass.lastIndexOf('.') + 1);

        Bag probe = null;
        Class<?> cls = null;
        try {
            // forNameUnhandled:找不到類別在這裡是預期中的正常結果(衍生版沒有這種袋子),
            // 用 forName 會被 Game.reportException() 當成錯誤回報。
            cls = Reflection.forNameUnhandled(pkg + simpleName);
        } catch (Exception e) {
            // 靜默略過,下面會快取 null
        }
        if (cls != null && Bag.class.isAssignableFrom(cls)) {
            Object instance = Reflection.newInstance(cls);
            if (instance instanceof Bag) {
                probe = (Bag) instance;
            }
        }

        PROBES.put(simpleName, probe);
        return probe;
    }
}
