package com.spd.mod.items;

/**
 * 標記「使用後不會被消耗」的 Mod 道具。
 *
 * 一般道具的使用流程,是由原生實作對「背包」做 detach 來扣掉數量(Food 的 EAT、Potion 的 DRINK…),
 * 因此收在 Loot storage 裡的道具必須先搬進背包才消耗得掉,見 ModLootStorage.useSingle()。
 * 但這類道具根本不走扣數量那條路,搬進背包的唯一效果就是「用完以後它留在背包裡、無法自動收回 storage」。
 *
 * 實作本介面的道具會直接在 storage 內就地發動,完全不經過背包。
 * 實作前請確認:該道具的動作不依賴自己在背包裡,也不會 detach 自己。
 */
public interface ModReusable {
}
