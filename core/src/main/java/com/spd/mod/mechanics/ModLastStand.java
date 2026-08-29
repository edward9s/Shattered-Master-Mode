package com.spd.mod.mechanics;

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
 * Last Stand sits behind normal shields. When damage handled by ShieldBuff would
 * otherwise reduce the bearer to 0 HP or below, it limits that damage so the
 * bearer remains at 1 HP, immediately grants blessed-Ankh-style invulnerability
 * plus Bless, then restores the bearer to 50% HP at the next actor opportunity.
 *
 * This does not guarantee survival. Damage which bypasses ShieldBuff (notably
 * Hunger), direct die() calls, and other special death mechanics can still kill
 * the bearer.
 */
public class ModLastStand extends ShieldBuff {

    private static final String RECOVERY_PENDING = "recovery_pending";

    private boolean recoveryPending;

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
        actPriority = VFX_PRIO;

        // Last Stand only sees damage left after every ordinary shield.
        shieldUsePriority = -1000;
        detachesAtZero = false;
    }

    @Override
    public int shielding() {
        // ShieldBuff.processDamage only invokes absorbDamage on buffs reporting
        // positive shielding. This point is a trigger token; absorbDamage below
        // never consumes it as ordinary shielding.
        return target != null && target.isAlive() ? 1 : 0;
    }

    @Override
    public int absorbDamage(int dmg) {
        if (target == null
                || !target.isAlive()
                || target.HP <= 0
                || recoveryPending
                || dmg < target.HP) {
            return dmg;
        }

        recoveryPending = true;

        // Protect the 1-HP interval before this high-priority buff gets to act.
        Buff.prolong(target, Invulnerability.class, Invulnerability.DURATION);
        Buff.prolong(target, Bless.class, Bless.DURATION);

        // Run as soon as the current damage-dealing actor yields.
        timeToNow();

        // The current damage call will subtract this value after processDamage
        // returns, leaving the bearer at exactly 1 HP.
        return Math.max(0, target.HP - 1);
    }

    @Override
    public boolean act() {
        if (recoveryPending) {
            recoveryPending = false;

            if (target != null && target.isAlive() && target.HP > 0) {
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
        }

        spend(TICK);
        return true;
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
                + "Last Stand limits that damage to leave 1 HP, grants 3 turns of invulnerability and 30 turns of Bless, "
                + "then restores HP to 50%. This protects against normal damage sources including falls and bleeding, "
                + "but does not guarantee survival: hunger, direct death effects, and other mechanics that bypass normal shielding can still kill the bearer.";
    }
}
