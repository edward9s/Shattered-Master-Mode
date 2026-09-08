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

    // Render-side observer state for one ordinary animated Hero attack.
    private transient Char observedAttackTarget;
    private transient boolean observedAttackWasInvulnerable;
    private transient boolean observedAttackHadInfiniteEvasion;

    {
        announced = true;
        revivePersists = true;
        color = 0xB06CFF;
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
        if (!instantKill) {
            clearObservedAttack();
        }
        ensureAccuracyObserver();
        BuffIndicator.refreshHero();
    }

    public void toggleInfiniteAccuracy() {
        infiniteAccuracy = !infiniteAccuracy;
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
        clearObservedAttack();
        super.detach();
        BuffIndicator.refreshHero();
    }

    @Override
    public int icon() {
        return ModBuffIconCompat.get("DUEL_CLEAVE");
    }

    @Override
    public void tintIcon(Image icon) {
        icon.hardlight(instantKill ? 0xFF4444 : 0xB06CFF);
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
        return "Instant Kill: " + (instantKill ? "ON" : "OFF")
                + ". When ON, successful normal attacks kill the target. Infinite Accuracy: "
                + (infiniteAccuracy ? "ON" : "OFF")
                + ". When ON, normal attacks effectively cannot miss. Tap to configure.";
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
     * Resolves an attack which an external Mod combat path attempted but the
     * engine did not accept as a hit. The caller supplies no Instant Kill policy:
     * this class alone decides whether the block was invulnerability and whether
     * Instant Kill is enabled.
     *
     * @return true only when Instant Kill converted the blocked attack into a
     * successful native death path.
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

    /** Runs this buff's native-death execution after its own policy has accepted a hit. */
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
            if (currentTarget != hero && currentTarget.isAlive()) {
                if (observedAttackTarget != currentTarget) {
                    observedAttackTarget = currentTarget;
                    observedAttackWasInvulnerable = currentTarget.isInvulnerable(hero.getClass());
                    observedAttackHadInfiniteEvasion =
                            ModCombatCompat.hasInfiniteEvasionAgainst(currentTarget, hero);
                } else {
                    // Never overwrite a true snapshot after a consumable defense
                    // response (such as Monk Focus) removes its own evasion state.
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

        if (attackedTarget == null
                || !hero.isAlive()
                || !attackedTarget.isAlive()) {
            return;
        }

        // Vanilla Char.attack checks invulnerability before it performs hit(), so
        // an invulnerable target never receives a native hit roll. Preserve normal
        // misses by explicitly rolling Char.hit when Infinite Accuracy is OFF.
        // Only a confirmed hit may invoke Instant Kill through invulnerability.
        if (wasInvulnerable) {
            if (instantKill
                    && (infiniteAccuracy || ModCombatCompat.rollNormalHeroHit(hero, attackedTarget))) {
                executeInstantKill(attackedTarget);
            }
            return;
        }

        // Engine-level infinite evasion beats even Char.INFINITE_ACCURACY before
        // ChampionEnemy accuracy factors are applied. Some defenders (notably
        // Monk Focus) consume that state from defenseVerb() after the native miss,
        // so use the pre-miss snapshot instead of asking defenseSkill again here.
        if (infiniteAccuracy
                && hadInfiniteEvasion
                && ModCombatCompat.forceHeroHit(hero, attackedTarget, 1f, 0f)) {
            if (hero.subClass == HeroSubClass.GLADIATOR) {
                Buff.affect(hero, Combo.class).hit(attackedTarget);
            }
            if (hero.heroClass == HeroClass.DUELIST) {
                ModCombatCompat.addDuelistComboHit(hero, attackedTarget);
            }
        }
    }

    private void clearObservedAttack() {
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
        clearObservedAttack();
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
