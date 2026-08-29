package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bless;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invulnerability;
import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;

/**
 * Permanent Master Mode emergency-survival buff.
 *
 * Last Stand checks the bearer once per tick. If the bearer is still alive and
 * below 10% HP, it restores HP to 50% and grants the same short invulnerability
 * duration used by a blessed Ankh, plus a normal Bless effect.
 *
 * This is intentionally not a death-prevention hook. A lethal hit or special
 * death effect can kill the bearer before Last Stand gets a chance to act.
 */
public class ModLastStand extends Buff {

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
        actPriority = VFX_PRIO;
    }

    @Override
    public boolean act() {
        if (target != null
                && target.isAlive()
                && target.HP > 0
                && target.HP * 10 < target.HT) {
            triggerLastStand();
        }

        spend(TICK);
        return true;
    }

    private void triggerLastStand() {
        int targetHP = Math.max(1, (target.HT + 1) / 2);
        int healed = Math.max(0, targetHP - target.HP);
        target.HP = Math.max(target.HP, targetHP);

        Buff.prolong(target, Invulnerability.class, Invulnerability.DURATION);
        Buff.prolong(target, Bless.class, Bless.DURATION);

        if (healed > 0 && target.sprite != null) {
            target.sprite.showStatusWithIcon(
                    CharSprite.POSITIVE,
                    Integer.toString(healed),
                    FloatingText.HEALING);
        }
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
        return "Permanent Master Mode buff. While the bearer is still alive, Last Stand checks once per tick. "
                + "If HP is below 10%, it restores HP to 50%, grants 3 turns of invulnerability, and grants 30 turns of Bless. "
                + "Last Stand does not intercept lethal damage and does not guarantee survival: a sufficiently large hit or a special death effect can kill the bearer before it activates.";
    }
}
