package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ShieldBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.Flare;
import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.spd.mod.items.WndModLoot;
import com.spd.mod.journal.ModLastStandOverlay;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;

import java.lang.reflect.Field;

/**
 * Permanent Master Mode survival buff with built-in Loot storage and UI.
 *
 * The persistent Last Stand object remains a plain Buff. A hidden ShieldBuff
 * hook performs pre-damage interception after save restoration has completed.
 * If normal shield-handled damage would be lethal, the hook limits it to leave
 * 1 HP and schedules Last Stand to restore the bearer to 50% HP and apply the
 * target game's PotionOfHealing.cure() behavior. Last Stand does not grant
 * invulnerability. It also recovers any living bearer that reaches exactly
 * 1 HP through a mechanic which bypasses normal shielding.
 *
 * When attached to the Hero, the same buff also owns shared Loot storage. Tapping
 * its buff icon opens the Loot / Put / Take / Console panel; long-press/right-click
 * keeps the normal buff-description behavior.
 *
 * This does not guarantee survival. Damage which bypasses normal shielding can
 * still kill if it skips directly past 1 HP, and direct die() calls or other
 * special death mechanics can bypass the protection.
 */
public class ModLastStand extends Buff {

    private static final String STORAGE = "storage";

    private ModLootStorage storage = new ModLootStorage();

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
        actPriority = VFX_PRIO;
    }

    /**
     * Uses Char.buffs(Class), whose erased return type is stable across the
     * supported SPD forks. Injectable code must not rely on Char.buff(Class),
     * whose compiled return descriptor differs in older forks.
     */
    public static ModLastStand find(Char ch) {
        if (ch == null) {
            return null;
        }
        for (ModLastStand lastStand : ch.buffs(ModLastStand.class)) {
            return lastStand;
        }
        return null;
    }

    private static LethalShieldHook findHook(Char ch) {
        if (ch == null) {
            return null;
        }
        for (LethalShieldHook hook : ch.buffs(LethalShieldHook.class)) {
            return hook;
        }
        return null;
    }

    public boolean isAttached() {
        return target != null && find(target) == this;
    }

    public ModLootStorage storage() {
        return storage;
    }

    public void open() {
        if (target instanceof Hero && target == Dungeon.hero) {
            Hero hero = (Hero) target;
            storage.reclaimPending(hero);
            GameScene.show(new WndModLoot(storage, name(), WndModLoot.Mode.USE));
        }
    }

    @Override
    public void fx(boolean on) {
        if (on) {
            ModLastStandOverlay.ensureInstalled();

            // Char.updateSpriteState() iterates the buff set while calling fx().
            // Do not attach another buff here; just schedule Last Stand to run
            // immediately once actor processing resumes.
            timeToNow();
        }
    }

    private void ensureLethalHook() {
        if (target != null && findHook(target) == null) {
            LethalShieldHook.attachRuntime(target);
        }
    }

    private void armRecovery() {
        if (target == null || !target.isAlive()) {
            return;
        }

        // Schedule recovery to happen as soon as actors can run again, after
        // the lethal hit has been limited to leave the target at 1 HP.
        timeToNow();
    }

    private void recoverFromOneHP() {
        if (target == null || !target.isAlive() || target.HP != 1) {
            return;
        }

        int recoveredHP = Math.max(1, (target.HT + 1) / 2);
        int healed = Math.max(0, recoveredHP - target.HP);
        target.HP = recoveredHP;

        // Use the target game's healing-potion cure semantics directly. This
        // intentionally does not invoke PotionOfHealing.heal() or reset hunger.
        PotionOfHealing.cure(target);

        if (target.sprite != null) {
            new Flare(8, 32).color(0xFFFF66, true).show(target.sprite, 2f);
        }

        if (healed > 0 && target.sprite != null) {
            target.sprite.showStatusWithIcon(
                    CharSprite.POSITIVE,
                    Integer.toString(healed),
                    FloatingText.HEALING);
        }
    }

    @Override
    public boolean act() {
        // Installing the hidden shield hook here avoids mutating the target's
        // buff collection while save restoration or sprite-state iteration runs.
        ensureLethalHook();
        ModLastStandOverlay.ensureInstalled();

        if (target != null && target.isAlive() && target.HP == 1) {
            recoverFromOneHP();
        }

        spend(TICK);
        return true;
    }

    @Override
    public void detach() {
        if (target instanceof Hero && Dungeon.level != null) {
            Hero hero = (Hero) target;
            storage.reclaimPending(hero);
            storage.dump(hero);
        }
        if (target != null) {
            Buff.detach(target, LethalShieldHook.class);
        }
        super.detach();
        BuffIndicator.refreshHero();
    }

    @Override
    public int icon() {
        return ModBuffIconCompat.get("AMULET");
    }

    @Override
    public void tintIcon(Image icon) {
        icon.hardlight(0xFFD15C);
    }

    @Override
    public String iconTextDisplay() {
        return "L";
    }

    @Override
    public String name() {
        return "Last Stand";
    }

    @Override
    public String desc() {
        return "Lethal damage handled by normal shielding leaves 1 HP, then restores 50% HP and cures ailments. "
                + "Grants no invulnerability; some direct death effects can bypass it. Tap to open Loot storage.";
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(STORAGE, storage);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        Object restored = bundle.get(STORAGE);
        if (restored instanceof ModLootStorage) {
            storage = (ModLootStorage) restored;
        } else {
            // Old Last Stand saves predate Loot storage; treat a missing payload as empty storage.
            storage = new ModLootStorage();
        }
    }

    /** Hidden ShieldBuff bridge; restored copies refuse to attach. */
    public static class LethalShieldHook extends ShieldBuff {

        private boolean restoredFromBundle;

        {
            try {
                Field field = ShieldBuff.class.getDeclaredField("shieldUsePriority");
                field.setAccessible(true);
                field.setInt(this, -1000);
            } catch (ReflectiveOperationException | SecurityException ignored) {
                // Older forks such as RKA do not expose shield-use priority.
            }
        }

        static void attachRuntime(Char target) {
            new LethalShieldHook().attachTo(target);
        }

        @Override
        public boolean attachTo(Char target) {
            if (restoredFromBundle) {
                restoredFromBundle = false;
                return false;
            }
            return super.attachTo(target);
        }

        @Override
        public void restoreFromBundle(Bundle bundle) {
            super.restoreFromBundle(bundle);
            restoredFromBundle = true;
        }

        @Override
        public int shielding() {
            return target != null
                    && target.isAlive()
                    && ModLastStand.find(target) != null
                    ? 1
                    : 0;
        }

        @Override
        public int absorbDamage(int dmg) {
            ModLastStand lastStand = ModLastStand.find(target);

            if (lastStand == null
                    || !target.isAlive()
                    || target.HP <= 0
                    || dmg < target.HP) {
                return dmg;
            }

            lastStand.armRecovery();
            return Math.max(0, target.HP - 1);
        }

        @Override
        public int icon() {
            return ModBuffIconCompat.get("NONE");
        }
    }
}
