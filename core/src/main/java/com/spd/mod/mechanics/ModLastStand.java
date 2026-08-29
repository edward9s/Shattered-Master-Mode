package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bless;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invulnerability;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ShieldBuff;
import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.watabou.utils.Bundle;

/**
 * Permanent Master Mode emergency-survival buff.
 *
 * The persistent Last Stand object remains a plain Buff so save restoration does
 * not depend on ShieldBuff state. A runtime-only LethalShieldHook performs the
 * actual pre-damage interception. If normal shield-handled damage would be
 * lethal, the hook limits it to leave 1 HP, grants blessed-Ankh-style
 * invulnerability plus Bless, and schedules Last Stand to restore the bearer to
 * 50% HP. Last Stand also recovers any living bearer that reaches exactly 1 HP
 * through a mechanic which bypasses normal shielding.
 *
 * This does not guarantee survival. Damage which bypasses normal shielding can
 * still kill if it skips directly past 1 HP, and direct die() calls or other
 * special death mechanics can bypass the protection.
 */
public class ModLastStand extends Buff {

    private static final String RECOVERY_PENDING = "recovery_pending";

    private boolean recoveryPending;

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
        actPriority = VFX_PRIO;
    }

    @Override
    public boolean attachTo(Char target) {
        if (!super.attachTo(target)) {
            return false;
        }
        ensureLethalHook();
        return true;
    }

    @Override
    public void fx(boolean on) {
        if (on) {
            ensureLethalHook();
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

        recoveryPending = true;
        Buff.prolong(target, Invulnerability.class, Invulnerability.DURATION);
        Buff.prolong(target, Bless.class, Bless.DURATION);
        timeToNow();
    }

    private void recoverFromOneHP() {
        if (target == null || !target.isAlive() || target.HP != 1) {
            recoveryPending = false;
            return;
        }

        if (!recoveryPending) {
            Buff.prolong(target, Invulnerability.class, Invulnerability.DURATION);
            Buff.prolong(target, Bless.class, Bless.DURATION);
        }

        recoveryPending = false;

        int targetHP = Math.max(1, (target.HT + 1) / 2);
        int healed = Math.max(0, targetHP - target.HP);
        target.HP = Math.max(target.HP, targetHP);

        if (healed > 0 && target.sprite != null) {
            target.sprite.showStatusWithIcon(
                    CharSprite.POSITIVE,
                    Integer.toString(healed),
                    FloatingText.HEALING);
        }
    }

    @Override
    public boolean act() {
        ensureLethalHook();

        if (target != null && target.isAlive() && target.HP == 1) {
            recoverFromOneHP();
        } else if (recoveryPending) {
            recoveryPending = false;
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
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(RECOVERY_PENDING, recoveryPending);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        recoveryPending = bundle.getBoolean(RECOVERY_PENDING);
    }

    @Override
    public int icon() {
        return BuffIndicator.ANKH;
    }

    @Override
    public String name() {
        return "Last Stand";
    }

    @Override
    public String desc() {
        return "Permanent Master Mode buff. If damage handled by the normal shielding system would be lethal, "
                + "Last Stand limits that damage to leave 1 HP and immediately grants 3 turns of invulnerability and 30 turns of Bless. "
                + "Whenever the bearer is alive at exactly 1 HP when Last Stand acts, it restores HP to 50% and grants those effects if they were not already applied. "
                + "This does not guarantee survival: damage that bypasses normal shielding can still kill if it skips past 1 HP, and direct death effects can also bypass Last Stand.";
    }

    /** Runtime-only ShieldBuff bridge; restored copies refuse to attach. */
    public static class LethalShieldHook extends ShieldBuff {

        private boolean restoredFromBundle;

        {
            revivePersists = false;
            shieldUsePriority = -1000;
            detachesAtZero = false;
        }

        static void attachRuntime(Char target) {
            LethalShieldHook hook = new LethalShieldHook();
            hook.attachTo(target);
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

            int maxDamage = Math.max(0, target.HP - 1);
            lastStand.armRecovery();
            return maxDamage;
        }

        @Override
        public int icon() {
            return BuffIndicator.NONE;
        }

        @Override
        public String name() {
            return "\u200B";
        }

        @Override
        public String desc() {
            return "";
        }

        @Override
        public boolean act() {
            diactivate();
            return true;
        }
    }
}
