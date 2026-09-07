package com.spd.mod.mechanics;

/**
 * Legacy save-compatibility alias for the former standalone Loot buff.
 *
 * New UI and behavior live entirely in ModLastStand. Keeping this class allows
 * existing saves that serialized ModLootBuff to restore without a missing-class
 * failure; restored instances inherit Last Stand's name, icon, survival logic,
 * Loot storage, and Loot UI.
 */
@Deprecated
public class ModLootBuff extends ModLastStand {
}
