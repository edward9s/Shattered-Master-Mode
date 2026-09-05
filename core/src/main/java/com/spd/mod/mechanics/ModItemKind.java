package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.items.Item;

import java.util.HashMap;
import java.util.Map;

/**
 * 用「類別名」在執行期做型別判斷,不在編譯期綁定 SPD 的特定類別。
 *
 * 套件路徑由 Item 自己推導,不寫死字串;某個衍生版沒有(或改名了)該類別時一律回傳 false,
 * 頂多是少了一條判斷,不會整包編不過。
 */
public class ModItemKind {

    /** 類別路徑,相對於 Item 所在的套件。 */
    public static final String WAND            = "wands.Wand";
    public static final String MISSILE_WEAPON  = "weapon.missiles.MissileWeapon";

    private static final Map<String, Class<?>> CACHE = new HashMap<>();

    public static boolean is(Item item, String relativeName) {
        if (item == null) {
            return false;
        }
        Class<?> cls = resolve(relativeName);
        return cls != null && cls.isInstance(item);
    }

    private static Class<?> resolve(String relativeName) {
        if (CACHE.containsKey(relativeName)) {
            return CACHE.get(relativeName);
        }

        String itemClass = Item.class.getName();
        String pkg = itemClass.substring(0, itemClass.lastIndexOf('.') + 1);

        Class<?> cls = null;
        try {
            // Use the JVM/Android framework API directly. Target-side helper wrappers such as
            // com.watabou.utils.Reflection.forNameUnhandled() may exist in source but be removed
            // from a release APK by R8 when the target itself never calls them.
            cls = Class.forName(pkg + relativeName);
        } catch (ClassNotFoundException e) {
            // 某個衍生版沒有這個類別是正常情況;快取 null,之後不再重試。
        }

        CACHE.put(relativeName, cls);
        return cls;
    }
}
