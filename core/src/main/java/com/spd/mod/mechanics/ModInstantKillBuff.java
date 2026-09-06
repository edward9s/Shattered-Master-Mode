package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ChampionEnemy;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;

import java.util.HashSet;

/** Permanent Hero buff that instantly kills enemies hit by normal attacks. */
public class ModInstantKillBuff extends ChampionEnemy {

    {
        announced = true;
        revivePersists = true;
        color = 0xFF4444;
    }

    @Override
    public boolean attachTo(Char target) {
        if (!(target instanceof Hero)) {
            return false;
        }
        return super.attachTo(target);
    }

    @Override
    public void fx(boolean on) {
        // Do not inherit ChampionEnemy's aura. This buff only changes attack procs.
    }

    @Override
    public boolean act() {
        // No periodic work is required; the buff is driven by attackProc().
        diactivate();
        return true;
    }

    @Override
    public void detach() {
        super.detach();
        BuffIndicator.refreshHero();
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
        return "Permanent Master Mode buff for the Hero. Every successful normal attack that reaches the standard attack proc path, including melee and thrown weapons, invokes the enemy's native death behavior. Missed attacks and non-attack damage are unaffected.";
    }

    @Override
    public void onAttackProc(Char enemy) {
        if (target instanceof Hero
                && enemy != null
                && enemy.isAlive()
                && enemy.alignment == Char.Alignment.ENEMY) {
            ModCombatCompat.kill(enemy, target);
        }
    }

    @Override
    public HashSet<Class> immunities() {
        // ChampionEnemy normally grants AllyBuff immunity; this debug buff must not.
        return new HashSet<>();
    }

    @Override
    public HashSet<Class> resistances() {
        return new HashSet<>();
    }
}
