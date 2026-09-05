package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mod 視窗共用的物品排序規則,讓卷軸內的清單在每個視窗看起來都一致。
 *
 * 先分組:Mod 自訂物品 → 魔法袋 → 藥劑筒 → 卷軸筒 → 絨布袋 → 其他,
 * 同組內再用遊戲原生的 {@link Generator.Category#order(Item)}(與背包/煉金介面同一套次序),
 * 最後用 Generator 類別表與完整類別名稱提供穩定的同類排序。
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

            int nativeOrder = Generator.Category.order(lhs) - Generator.Category.order(rhs);
            if (nativeOrder != 0) {
                return nativeOrder;
            }

            int generatorOrder = generatorClassOrder(lhs) - generatorClassOrder(rhs);
            if (generatorOrder != 0) {
                return generatorOrder;
            }

            // Final deterministic fallback for modded/derived items that are absent from Generator tables.
            return lhs.getClass().getName().compareTo(rhs.getClass().getName());
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
     * Returns a stable order within the game's own Generator tables. The category enum is stable
     * across the supported SPD versions and its public classes arrays already encode canonical
     * per-item ordering for scrolls, potions, rings, etc. Unknown derived items fall through.
     */
    private static int generatorClassOrder(Item item) {
        if (item == null) {
            return Integer.MAX_VALUE;
        }
        int categoryBase = 0;
        for (Generator.Category category : Generator.Category.values()) {
            Class<?>[] classes = category.classes;
            if (classes != null) {
                for (int i = 0; i < classes.length; i++) {
                    if (classes[i] == item.getClass()) {
                        return categoryBase + i;
                    }
                }
                categoryBase += classes.length + 1;
            } else {
                categoryBase++;
            }
        }
        return Integer.MAX_VALUE;
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
        try {
            // Use only JVM/Android framework reflection here. A target release APK may have
            // stripped unused com.watabou.utils.Reflection wrapper methods even when they exist
            // in that fork's source tree.
            Class<?> cls = Class.forName(pkg + simpleName);
            if (Bag.class.isAssignableFrom(cls)) {
                Object instance = cls.getDeclaredConstructor().newInstance();
                if (instance instanceof Bag) {
                    probe = (Bag) instance;
                }
            }
        } catch (ReflectiveOperationException e) {
            // 某個衍生版沒有這個袋子,或它沒有可用的空建構子,都只代表這個分組不存在。
        }

        PROBES.put(simpleName, probe);
        return probe;
    }
}
