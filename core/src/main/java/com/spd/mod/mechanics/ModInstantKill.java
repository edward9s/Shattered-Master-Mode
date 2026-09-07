package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ChampionEnemy;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Combo;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Preparation;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.effects.Wound;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.spd.mod.journal.ModTotalInfoOverlay;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;

import java.lang.reflect.Field;
import java.util.HashSet;

/** Permanent Hero combat buff with configurable instant-kill and extreme-accuracy effects. */
public class ModInstantKill extends ChampionEnemy {

    private static final String INSTANT_KILL = "instant_kill";
    private static final String INFINITE_ACCURACY = "infinite_accuracy";

    private static Field currentActorField;
    private static AccuracyObserver accuracyObserver;
    private static boolean observerInstallPending;

    private boolean instantKill;
    private boolean infiniteAccuracy;

    // Only used by the render-side observer for ordinary animated Hero attacks.
    private transient Char observedInfiniteEvasionTarget;

    {
        announced = true;
        revivePersists = true;
        color = 0xFF4444;
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

    public boolean infiniteAccuracyEnabled() {
        return infiniteAccuracy;
    }

    public void toggleInstantKill() {
        instantKill = !instantKill;
        BuffIndicator.refreshHero();
    }

    public void toggleInfiniteAccuracy() {
        infiniteAccuracy = !infiniteAccuracy;
        if (!infiniteAccuracy) {
            observedInfiniteEvasionTarget = null;
        }
        ensureAccuracyObserver();
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
        ensureAccuracyObserver();
        return true;
    }

    @Override
    public void fx(boolean on) {
        // Do not inherit ChampionEnemy's aura. This buff only changes combat behavior.
        if (on) {
            ModTotalInfoOverlay.ensureInstalled();
            ensureAccuracyObserver();
        }
    }

    @Override
    public boolean act() {
        ModTotalInfoOverlay.ensureInstalled();
        ensureAccuracyObserver();
        // No periodic actor work is required; attack hooks and the lightweight
        // render observer drive both effects.
        diactivate();
        return true;
    }

    @Override
    public void detach() {
        observedInfiniteEvasionTarget = null;
        super.detach();
        BuffIndicator.refreshHero();
    }

    @Override
    public int icon() {
        return BuffIndicator.DUEL_CLEAVE;
    }

    @Override
    public void tintIcon(Image icon) {
        icon.hardlight(0x66FF33);
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
                + "; when enabled, normal accuracy receives an extreme multiplier. For ordinary animated Hero attacks, targets whose defense is engine-level INFINITE_EVASION are detected after the native forced miss and receive one Mod-side successful-hit pass instead. Vanilla combat classes are not patched.";
    }

    @Override
    public void onAttackProc(Char enemy) {
        if (instantKill
                && target instanceof Hero
                && enemy != null
                && enemy != target
                && enemy.isAlive()) {
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
        return current == target ? Float.MAX_VALUE : 1f;
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

    private static void ensureAccuracyObserver() {
        if (!(ShatteredPixelDungeon.scene() instanceof GameScene)) {
            return;
        }
        if (accuracyObserver != null
                && accuracyObserver.exists
                && accuracyObserver.parent == ShatteredPixelDungeon.scene()) {
            return;
        }
        if (observerInstallPending) {
            return;
        }
        observerInstallPending = true;

        ShatteredPixelDungeon.runOnRenderThread(new Callback() {
            @Override
            public void call() {
                observerInstallPending = false;
                if (!(ShatteredPixelDungeon.scene() instanceof GameScene)
                        || Dungeon.hero == null
                        || ModInstantKill.find(Dungeon.hero) == null) {
                    return;
                }
                Group scene = (Group) ShatteredPixelDungeon.scene();
                if (accuracyObserver == null
                        || !accuracyObserver.exists
                        || accuracyObserver.parent != scene) {
                    accuracyObserver = new AccuracyObserver();
                    scene.addToFront(accuracyObserver);
                }
            }
        });
    }

    private void observeHeroAttack(Hero hero) {
        Char currentTarget = ModCombatCompat.heroAttackTarget(hero);

        if (currentTarget != null) {
            if (infiniteAccuracy
                    && currentTarget != hero
                    && currentTarget.isAlive()
                    && ModCombatCompat.hasInfiniteEvasionAgainst(currentTarget, hero)) {
                observedInfiniteEvasionTarget = currentTarget;
            } else {
                observedInfiniteEvasionTarget = null;
            }
            return;
        }

        Char missedTarget = observedInfiniteEvasionTarget;
        observedInfiniteEvasionTarget = null;
        if (!infiniteAccuracy
                || missedTarget == null
                || !hero.isAlive()
                || !missedTarget.isAlive()
                || !ModCombatCompat.hasInfiniteEvasionAgainst(missedTarget, hero)) {
            return;
        }

        if (ModCombatCompat.forceHeroHit(hero, missedTarget, 1f, 0f)) {
            if (hero.subClass == HeroSubClass.GLADIATOR) {
                Buff.affect(hero, Combo.class).hit(missedTarget);
            }
            if (hero.heroClass == HeroClass.DUELIST) {
                ModCombatCompat.addDuelistComboHit(hero, missedTarget);
            }
        }
    }

    private static class AccuracyObserver extends Gizmo {
        @Override
        public void update() {
            super.update();

            if (!(ShatteredPixelDungeon.scene() instanceof GameScene)
                    || parent != ShatteredPixelDungeon.scene()
                    || Dungeon.hero == null) {
                killAndErase();
                if (accuracyObserver == this) {
                    accuracyObserver = null;
                }
                return;
            }

            ModInstantKill buff = ModInstantKill.find(Dungeon.hero);
            if (buff == null) {
                killAndErase();
                if (accuracyObserver == this) {
                    accuracyObserver = null;
                }
                return;
            }

            buff.observeHeroAttack(Dungeon.hero);
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
        observedInfiniteEvasionTarget = null;
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
