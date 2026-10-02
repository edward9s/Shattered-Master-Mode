package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.spd.mod.journal.WndForceHitInfo;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;


/** Permanent Char buff with a configurable forced-hit effect. */
public class ModForceHit extends Buff {

    private static final String FORCE_HIT_ENABLED = "force_hit_enabled";
    private boolean forceHitEnabled = true;

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
    }

    /** Returns the attached buff regardless of whether its effect is enabled. */
    public static ModForceHit findAttached(Char ch) {
        if (ch == null) {
            return null;
        }
        for (ModForceHit buff : ch.buffs(ModForceHit.class)) {
            return buff;
        }
        return null;
    }

    /** Returns the active effect; a disabled but attached buff returns null. */
    public static ModForceHit find(Char ch) {
        ModForceHit buff = findAttached(ch);
        return buff != null && buff.forceHitEnabled ? buff : null;
    }

    /** Returns whether Force Hit is actively enabled for this attacker. */
    public static boolean isForceHitEnabled(Char attacker) {
        return attacker != null
                && attacker.isAlive()
                && find(attacker) != null;
    }

    /**
     * The selected Char.hit check calls this before native accuracy/evasion logic.
     * Force Hit changes hit resolution only; it does not bypass attack-level
     * invulnerability or replace native attack presentation.
     */
    public static boolean forceHitCheck(Char attacker, Char defender) {
        return attacker != null
                && defender != null
                && attacker != defender
                && isForceHitEnabled(attacker)
                && defender.isAlive();
    }

    public boolean forceHitEnabled() {
        return forceHitEnabled;
    }

    public void toggleForceHit() {
        forceHitEnabled = !forceHitEnabled;
        BuffIndicator.refreshHero();
    }

    public void openInfo() {
        if (target != null && findAttached(target) == this) {
            GameScene.show(new WndForceHitInfo(this));
        }
    }

    @Override
    public boolean act() {
        spend(TICK);
        return true;
    }

    @Override
    public void detach() {
        super.detach();
        BuffIndicator.refreshHero();
    }

    @Override
    public int icon() {
        return ModBuffIconCompat.getFirst(
                "INVERT_MARK",
                "MARK",
                "HEART");
    }

    @Override
    public void tintIcon(Image icon) {
        icon.hardlight(forceHitEnabled ? 0x55CCFF : 0xAAAAAA);
    }

    @Override
    public String iconTextDisplay() {
        return "H";
    }

    @Override
    public String name() {
        return "Force Hit";
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public String desc() {
        return "Forces this character's hit checks to succeed. "
                + "It does not bypass attack-level invulnerability.";
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(FORCE_HIT_ENABLED, forceHitEnabled);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        // Saves from before the checkbox existed preserve the old always-ON behavior.
        forceHitEnabled = !bundle.contains(FORCE_HIT_ENABLED)
                || bundle.getBoolean(FORCE_HIT_ENABLED);
    }
}
