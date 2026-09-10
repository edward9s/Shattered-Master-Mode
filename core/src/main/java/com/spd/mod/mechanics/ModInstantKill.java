package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ChampionEnemy;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Preparation;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.Wound;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.spd.mod.journal.ModTotalInfoOverlay;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;

import java.util.HashSet;

/** Permanent Hero combat buff with a configurable Instant Kill effect. */
public class ModInstantKill extends ChampionEnemy {

    private static final String INSTANT_KILL = "instant_kill";

    private boolean instantKill = true;

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
        // ChampionEnemy is required because Char.attackProc() dispatches only to
        // ChampionEnemy buffs. This buff otherwise has no champion side effects.
        color = 0xFFFFFF;
    }

    /** Stable across supported SPD forks; avoids Char.buff(Class) ABI variance. */
    public static ModInstantKill find(Char ch) {
        if (ch == null) {
            return null;
        }
        for (ModInstantKill buff : ch.buffs(ModInstantKill.class)) {
            return buff;
        }
        return null;
    }

    public boolean instantKillEnabled() {
        return instantKill;
    }

    public void toggleInstantKill() {
        instantKill = !instantKill;
        BuffIndicator.refreshHero();
        ModTotalInfoOverlay.refreshIndicators();
    }

    @Override
    public boolean attachTo(Char target) {
        if (!(target instanceof Hero)) {
            return false;
        }
        if (!super.attachTo(target)) {
            return false;
        }
        ModTotalInfoOverlay.ensureInstalled();
        return true;
    }

    @Override
    public void fx(boolean on) {
        // Do not inherit ChampionEnemy's aura or actor tint.
        if (on) {
            ModTotalInfoOverlay.ensureInstalled();
        }
    }

    @Override
    public boolean act() {
        ModTotalInfoOverlay.ensureInstalled();
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
        return ModBuffIconCompat.get("DUEL_CLEAVE");
    }

    @Override
    public void tintIcon(Image icon) {
        icon.hardlight(instantKill ? 0xFF4444 : 0xAAAAAA);
    }

    @Override
    public String iconTextDisplay() {
        return "K";
    }

    @Override
    public String name() {
        return "Instant Kill";
    }

    @Override
    public String desc() {
        return "Successful Hero attacks kill their target while enabled. Tap to configure.";
    }

    @Override
    public void onAttackProc(Char enemy) {
        if (instantKill
                && target instanceof Hero
                && enemy != null
                && enemy != target
                && enemy.isAlive()) {
            executeInstantKill(enemy);
        }
    }

    /**
     * Resolves a Mod attack that was blocked by target invulnerability. Instant
     * Kill keeps ownership of this policy; Force Hit never bypasses
     * invulnerability by itself.
     */
    static boolean resolveBlockedAttack(Hero hero, Char enemy) {
        ModInstantKill buff = find(hero);
        if (buff == null
                || !buff.instantKill
                || buff.target != hero
                || hero == null
                || enemy == null
                || enemy == hero
                || !hero.isAlive()
                || !enemy.isAlive()
                || !enemy.isInvulnerable(hero.getClass())) {
            return false;
        }
        return buff.executeInstantKill(enemy);
    }

    private boolean executeInstantKill(Char enemy) {
        if (!(target instanceof Hero)
                || enemy == null
                || enemy == target
                || !enemy.isAlive()) {
            return false;
        }

        Wound.hit(enemy);
        if (!ModCombatCompat.kill(enemy, target)) {
            return false;
        }
        if (enemy.sprite != null) {
            enemy.sprite.showStatus(
                    CharSprite.NEGATIVE,
                    Messages.get(Preparation.class, "assassinated"));
        }
        return true;
    }

    @Override
    public float evasionAndAccuracyFactor() {
        return 1f;
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(INSTANT_KILL, instantKill);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        instantKill = bundle.getBoolean(INSTANT_KILL);
    }

    @Override
    public HashSet<Class> immunities() {
        return new HashSet<>();
    }

    @Override
    public HashSet<Class> resistances() {
        return new HashSet<>();
    }
}
