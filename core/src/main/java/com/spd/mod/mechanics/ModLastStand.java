package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ShieldBuff;
import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;

import java.lang.reflect.Field;

/**
 * Permanent Master Mode emergency-survival buff.
 *
 * The persistent Last Stand object remains a plain Buff. A hidden ShieldBuff
 * hook performs pre-damage interception after save restoration has completed.
 * If normal shield-handled damage would be lethal, the hook limits it to leave
 * 1 HP and schedules Last Stand to restore the bearer to 25% HP and cure the
 * same status ailments as a blessed Ankh. Unlike a blessed Ankh, Last Stand
 * does not grant invulnerability. Last Stand also recovers any living bearer
 * that reaches exactly 1 HP through a mechanic which bypasses normal shielding.
 *
 * This does not guarantee survival. Damage which bypasses normal shielding can
 * still kill if it skips directly past 1 HP, and direct die() calls or other
 * special death mechanics can bypass the protection.
 */
public class ModLastStand extends Buff {

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
        actPriority = VFX_PRIO;
    }

    @Override
    public void fx(boolean on) {
        if (on) {
            // Char.updateSpriteState() iterates the buff set while calling fx().
            // Do not attach another buff here; just schedule Last Stand to run
            // immediately once actor processing resumes.
            timeToNow();
        }
    }

    private void ensureLethalHook() {
        if (target != null && target.buff(LethalShieldHook.class) == null) {
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

        int recoveredHP = Math.max(1, target.HT / 4);
        int healed = Math.max(0, recoveredHP - target.HP);
        target.HP = recoveredHP;

        // Match blessed Ankh cleansing, but intentionally omit its temporary
        // Invulnerability buff. Hunger is not reset by PotionOfHealing.cure().
        PotionOfHealing.cure(target);

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

        if (target != null && target.isAlive() && target.HP == 1) {
            recoverFromOneHP();
        }

        spend(TICK);
        return true;
    }

    @Override
    public void detach() {
        if (target != null) {
            Buff.detach(target, LethalShieldHook.class);
        }
        super.detach();
        BuffIndicator.refreshHero();
    }

    @Override
    public int icon() {
        return BuffIndicator.BERSERK;
    }

    @Override
    public void tintIcon(Image icon) {
        icon.hardlight(1f, 0.8f, 0.2f);
    }

    @Override
    public String name() {
        return "Last Stand";
    }

    @Override
    public String desc() {
        return "Permanent Master Mode buff. If damage handled by the normal shielding system would be lethal, "
                + "Last Stand limits that damage to leave 1 HP. Whenever the bearer is alive at exactly 1 HP when Last Stand acts, "
                + "it restores HP to 25% and cures the same status ailments as a blessed Ankh, but grants no invulnerability and does not reset hunger. "
                + "This does not guarantee survival: damage that bypasses normal shielding can still kill if it skips past 1 HP, and direct death effects can also bypass Last Stand.";
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
                    && target.buff(ModLastStand.class) != null
                    ? 1
                    : 0;
        }

        @Override
        public int absorbDamage(int dmg) {
            ModLastStand lastStand = target == null
                    ? null
                    : target.buff(ModLastStand.class);

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
            return BuffIndicator.NONE;
        }
    }
}
