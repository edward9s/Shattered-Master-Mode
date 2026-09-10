package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ChampionEnemy;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Combo;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.utils.Callback;

import java.lang.reflect.Field;
import java.util.HashSet;

/** Permanent Hero buff that forces hit checks to succeed whenever the target can be hit. */
public class ModForceHit extends ChampionEnemy {

    private static Field currentActorField;
    private static AccuracyObserver accuracyObserver;
    private static boolean observerInstallPending;

    private static Char observedAttackTarget;
    private static boolean observedAttackWasInvulnerable;
    private static boolean observedAttackHadInfiniteEvasion;

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
        // ChampionEnemy is used only for its attacker accuracy factor.
        color = 0xFFFFFF;
    }

    /** Stable across supported SPD forks; avoids Char.buff(Class) ABI variance. */
    public static ModForceHit find(Char ch) {
        if (ch == null) {
            return null;
        }
        for (ModForceHit buff : ch.buffs(ModForceHit.class)) {
            return buff;
        }
        return null;
    }

    @Override
    public boolean attachTo(Char target) {
        if (!(target instanceof Hero)) {
            return false;
        }
        if (!super.attachTo(target)) {
            return false;
        }
        ensureAccuracyObserver();
        return true;
    }

    @Override
    public void fx(boolean on) {
        // Do not inherit ChampionEnemy's aura or actor tint.
        if (on) {
            ensureAccuracyObserver();
        }
    }

    @Override
    public boolean act() {
        ensureAccuracyObserver();
        spend(TICK);
        return true;
    }

    @Override
    public void detach() {
        clearObservedAttack();
        super.detach();
        BuffIndicator.refreshHero();
    }

    @Override
    public int icon() {
        return ModBuffIconCompat.get("AMULET");
    }

    @Override
    public void tintIcon(Image icon) {
        icon.hardlight(0x55CCFF);
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
    public String desc() {
        return "Forces Hero hit checks to succeed whenever the target can be hit. Invulnerability is not bypassed.";
    }

    @Override
    public float evasionAndAccuracyFactor() {
        if (target == null) {
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

    static void ensureAccuracyObserver() {
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
                        || find(Dungeon.hero) == null) {
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

    private static void observeHeroAttack(Hero hero) {
        Char currentTarget = ModCombatCompat.heroAttackTarget(hero);

        if (currentTarget != null) {
            if (currentTarget != hero && currentTarget.isAlive()) {
                if (observedAttackTarget != currentTarget) {
                    observedAttackTarget = currentTarget;
                    observedAttackWasInvulnerable = currentTarget.isInvulnerable(hero.getClass());
                    observedAttackHadInfiniteEvasion =
                            ModCombatCompat.hasInfiniteEvasionAgainst(currentTarget, hero);
                } else {
                    observedAttackWasInvulnerable |=
                            currentTarget.isInvulnerable(hero.getClass());
                    observedAttackHadInfiniteEvasion |=
                            ModCombatCompat.hasInfiniteEvasionAgainst(currentTarget, hero);
                }
            } else {
                clearObservedAttack();
            }
            return;
        }

        Char attackedTarget = observedAttackTarget;
        boolean wasInvulnerable = observedAttackWasInvulnerable;
        boolean hadInfiniteEvasion = observedAttackHadInfiniteEvasion;
        clearObservedAttack();

        if (find(hero) == null
                || attackedTarget == null
                || !hero.isAlive()
                || !attackedTarget.isAlive()
                || wasInvulnerable
                || !hadInfiniteEvasion) {
            return;
        }

        // Engine-level INFINITE_EVASION wins before ChampionEnemy factors are
        // applied. Replay only the successful-hit side after that specific miss.
        if (ModCombatCompat.forceHeroHit(hero, attackedTarget, 1f, 0f)) {
            if (hero.subClass == HeroSubClass.GLADIATOR) {
                Buff.affect(hero, Combo.class).hit(attackedTarget);
            }
            if (hero.heroClass == HeroClass.DUELIST) {
                ModCombatCompat.addDuelistComboHit(hero, attackedTarget);
            }
        }
    }

    private static void clearObservedAttack() {
        observedAttackTarget = null;
        observedAttackWasInvulnerable = false;
        observedAttackHadInfiniteEvasion = false;
    }

    private static class AccuracyObserver extends Gizmo {
        @Override
        public void update() {
            super.update();

            if (!(ShatteredPixelDungeon.scene() instanceof GameScene)
                    || parent != ShatteredPixelDungeon.scene()
                    || Dungeon.hero == null
                    || find(Dungeon.hero) == null) {
                clearObservedAttack();
                killAndErase();
                if (accuracyObserver == this) {
                    accuracyObserver = null;
                }
                return;
            }

            observeHeroAttack(Dungeon.hero);
        }
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
