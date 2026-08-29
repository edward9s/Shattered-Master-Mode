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
 * bearer remains at 1 HP and immediately grants blessed-Ankh-style
 * invulnerability plus Bless. Any time the bearer is alive at exactly 1 HP when
 * Last Stand acts, it restores the bearer to 50% HP.
 *
 * This does not guarantee survival. Damage which bypasses ShieldBuff can still
 * kill the bearer if it skips past 1 HP before Last Stand acts, and direct die()
 * calls or other special death mechanics can also bypass the protection.
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
        if (target != null && target.isAlive() && target.HP == 1) {
            // A lethal hit already granted these effects inside absorbDamage.
            // If some other mechanic merely left the bearer at 1 HP, grant them here.
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
        } else if (recoveryPending) {
            // The bearer may have been healed by another effect before Last Stand acted.
            recoveryPending = false;
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
                + "Last Stand limits that damage to leave 1 HP and immediately grants 3 turns of invulnerability and 30 turns of Bless. "
                + "Whenever the bearer is alive at exactly 1 HP when Last Stand acts, it restores HP to 50% and grants those effects if they were not already applied. "
                + "This does not guarantee survival: damage that bypasses normal shielding can still kill if it skips past 1 HP, and direct death effects can also bypass Last Stand.";
    }
}
