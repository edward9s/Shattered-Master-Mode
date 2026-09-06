package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
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

import java.lang.reflect.Field;
import java.util.HashSet;

/** Permanent Hero combat buff with configurable instant-kill and accuracy effects. */
public class ModInstantKillBuff extends ChampionEnemy {

    private static final String INSTANT_KILL = "instant_kill";
    private static final String INFINITE_ACCURACY = "infinite_accuracy";

    private static Field currentActorField;

    private boolean instantKill;
    private boolean infiniteAccuracy;

    {
        announced = true;
        revivePersists = true;
        color = 0xFF4444;
    }

    /** Stable across supported SPD forks; avoids Char.buff(Class) ABI variance. */
    public static ModInstantKillBuff find(Char ch) {
        if (ch == null) {
            return null;
        }
        for (ModInstantKillBuff buff : ch.buffs(ModInstantKillBuff.class)) {
            return buff;
        }
        return null;
    }

    public boolean instantKillEnabled() {
        return instantKill;
    }

    public boolean infiniteAccuracyEnabled() {
        return infiniteAccuracy;
    }

    public void toggleInstantKill() {
        instantKill = !instantKill;
        BuffIndicator.refreshHero();
    }

    public void toggleInfiniteAccuracy() {
        infiniteAccuracy = !infiniteAccuracy;
        BuffIndicator.refreshHero();
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
        // Do not inherit ChampionEnemy's aura. This buff only changes combat behavior.
        if (on) {
            ModTotalInfoOverlay.ensureInstalled();
        }
    }

    @Override
    public boolean act() {
        ModTotalInfoOverlay.ensureInstalled();
        // No periodic work is required; attack hooks drive both effects.
        diactivate();
        return true;
    }

    @Override
    public void detach() {
        super.detach();
        BuffIndicator.refreshHero();
    }

    @Override
    public int icon() {
        return BuffIndicator.DUEL_CLEAVE;
    }

    @Override
    public void tintIcon(Image icon) {
        icon.hardlight(0xCC2222);
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
        return "Permanent Master Mode combat buff for the Hero. Instant Kill is "
                + (instantKill ? "ON" : "OFF")
                + "; when enabled, every successful normal attack invokes the target's native death behavior, regardless of alignment, with the Assassin's execution hit effect and status text. Infinite Accuracy is "
                + (infiniteAccuracy ? "ON" : "OFF")
                + "; when enabled, the Hero's normal attack accuracy is multiplied by Char.INFINITE_ACCURACY. Both effects apply to melee and thrown attacks that use the standard Char.attack path.";
    }

    @Override
    public void onAttackProc(Char enemy) {
        if (instantKill
                && target instanceof Hero
                && enemy != null
                && enemy != target
                && enemy.isAlive()) {
            // Match Mod Assassin's impact cue, then reuse Preparation's native
            // localized assassination status text only when the native death path ran.
            Wound.hit(enemy);
            if (ModCombatCompat.kill(enemy, target) && enemy.sprite != null) {
                enemy.sprite.showStatus(
                        CharSprite.NEGATIVE,
                        Messages.get(Preparation.class, "assassinated"));
            }
        }
    }

    @Override
    public float evasionAndAccuracyFactor() {
        if (!infiniteAccuracy || target == null) {
            return 1f;
        }

        Actor current = currentActor();
        return current == target ? Char.INFINITE_ACCURACY : 1f;
    }

    private static Actor currentActor() {
        try {
            if (currentActorField == null) {
                currentActorField = Actor.class.getDeclaredField("current");
                currentActorField.setAccessible(true);
            }
            return (Actor) currentActorField.get(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(INSTANT_KILL, instantKill);
        bundle.put(INFINITE_ACCURACY, infiniteAccuracy);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        instantKill = bundle.getBoolean(INSTANT_KILL);
        infiniteAccuracy = bundle.getBoolean(INFINITE_ACCURACY);
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
