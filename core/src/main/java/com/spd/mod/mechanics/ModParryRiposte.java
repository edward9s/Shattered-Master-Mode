package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ChampionEnemy;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MonkEnergy;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.spd.mod.journal.ModTotalInfoOverlay;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;

import java.lang.reflect.Field;
import java.util.HashSet;

/**
 * Permanent Master Mode combat buff.
 *
 * Total is itself a ChampionEnemy accuracy/evasion hook, so it only needs to
 * exist on the character that owns Total. This avoids the old global pairing
 * scheme which depended on every active Char carrying a synchronized helper.
 */
public class ModParryRiposte extends ChampionEnemy {

    private static final String RIPOSTE_ENABLED = "riposte_enabled";

    // Keys used by previous implementations; read only for save migration.
    private static final String LEGACY_MODE = "mode";
    private static final String LEGACY_OWNS_FOCUS = "owns_focus";

    private static Field currentActorField;

    private boolean riposteEnabled;
    private boolean removeLegacyFocus;

    {
        announced = true;
        revivePersists = true;
        actPriority = VFX_PRIO;
    }

    public boolean riposteEnabled() {
        return riposteEnabled;
    }

    public void setRiposteEnabled(boolean enabled) {
        riposteEnabled = enabled;
        BuffIndicator.refreshHero();
    }

    public void toggleRiposte() {
        setRiposteEnabled(!riposteEnabled);
    }

    @Override
    public boolean attachTo(Char target) {
        if (!super.attachTo(target)) {
            return false;
        }
        timeToNow();
        ModTotalInfoOverlay.ensureInstalled();
        return true;
    }

    @Override
    public void fx(boolean on) {
        // Do not attach helper buffs or create combat infrastructure here.
        // Char.updateSpriteState() calls fx() while iterating the live buff set.
        if (on) {
            ModTotalInfoOverlay.ensureInstalled();
        }
    }

    @Override
    public boolean act() {
        // Compatibility cleanup for saves from the old PARRY implementation.
        if (removeLegacyFocus && target != null) {
            MonkEnergy.MonkAbility.Focus.FocusBuff focus =
                    target.buff(MonkEnergy.MonkAbility.Focus.FocusBuff.class);
            if (focus != null) {
                focus.detach();
            }
            removeLegacyFocus = false;
        }

        // Total has no periodic runtime plumbing anymore.
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
        return riposteEnabled ? BuffIndicator.DUEL_CLEAVE : BuffIndicator.DUEL_GUARD;
    }

    @Override
    public void tintIcon(Image icon) {
        if (riposteEnabled) {
            icon.hardlight(0xFF5577);
        } else {
            icon.hardlight(0x55CCFF);
        }
    }

    @Override
    public String iconTextDisplay() {
        return riposteEnabled ? "R" : "P";
    }

    @Override
    public String name() {
        return "Total Parry / Riposte";
    }

    @Override
    public String desc() {
        if (riposteEnabled) {
            return "Permanent Master Mode buff. Total Parry always parries incoming attacks handled by the normal hit check. "
                    + "Riposte is ON: every attack parried by Total Parry immediately triggers a guaranteed normal "
                    + "counterattack, regardless of distance. Use the button below to turn riposte off.";
        } else {
            return "Permanent Master Mode buff. Total Parry always parries incoming attacks handled by the normal hit check. "
                    + "Riposte is OFF, so the buff only parries. Use the button below to turn riposte on.";
        }
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(RIPOSTE_ENABLED, riposteEnabled);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);

        if (bundle.contains(RIPOSTE_ENABLED)) {
            riposteEnabled = bundle.getBoolean(RIPOSTE_ENABLED);
        } else if (bundle.contains(LEGACY_MODE)) {
            riposteEnabled = "RIPOSTE".equals(bundle.getString(LEGACY_MODE));
        }

        removeLegacyFocus = bundle.contains(LEGACY_OWNS_FOCUS)
                && bundle.getBoolean(LEGACY_OWNS_FOCUS);

        // Run compatibility cleanup before normal character turns after load.
        timeToNow();
    }

    /**
     * Char.hit() invokes ChampionEnemy.evasionAndAccuracyFactor() once for the
     * attacker and once for the defender. The currently-processing Actor is the
     * attack source for normal Hero/Mob attacks. Because Total itself is only on
     * its owner, no cross-character callback pairing is required.
     */
    @Override
    public float evasionAndAccuracyFactor() {
        Char attacker = currentAttackSource();

        // If Total's owner is the current attack source, this is the attacker's
        // accuracy pass. Total must not modify its own accuracy.
        if (attacker == null || attacker == target) {
            return 1f;
        }

        if (target == null || !target.isAlive()) {
            return 1f;
        }

        if (riposteEnabled && attacker.isAlive()) {
            scheduleRiposte(target, attacker);
        }

        // Defender pass: make the normal hit check fail.
        return Float.POSITIVE_INFINITY;
    }

    private static Char currentAttackSource() {
        Actor current = currentActor();
        if (current instanceof RiposteActor) {
            return ((RiposteActor) current).riposter;
        }
        return current instanceof Char ? (Char) current : null;
    }

    private static Actor currentActor() {
        try {
            if (currentActorField == null) {
                currentActorField = Actor.class.getDeclaredField("current");
                currentActorField.setAccessible(true);
            }
            return (Actor) currentActorField.get(null);
        } catch (Exception ignored) {
            // If SPD changes this private implementation detail, fail closed:
            // do not guess an attacker and accidentally corrupt combat state.
            return null;
        }
    }

    private static void scheduleRiposte(Char riposter, Char attacker) {
        Actor.add(new RiposteActor(riposter, attacker));
    }

    private static void performRiposte(Char riposter, Char attacker) {
        ModParryRiposte buff = riposter.buff(ModParryRiposte.class);
        if (buff != null
                && buff.riposteEnabled
                && riposter.isAlive()
                && attacker.isAlive()) {
            // Intentionally no canAttack/range check. Total Riposte must always
            // be able to answer an attack that Total Parry intercepted.
            riposter.attack(attacker, 1f, 0f, Char.INFINITE_ACCURACY);
        }
    }

    /**
     * Holds Actor processing while a visible riposte animation is running, just
     * like vanilla character attacks do. The previous implementation removed
     * its scheduling Actor immediately and let later turns overlap the callback.
     */
    private static class RiposteActor extends Actor {

        private final Char riposter;
        private final Char attacker;
        private boolean waitingForAnimation;

        RiposteActor(Char riposter, Char attacker) {
            this.riposter = riposter;
            this.attacker = attacker;
            actPriority = VFX_PRIO;
        }

        @Override
        protected boolean act() {
            ModParryRiposte buff = riposter.buff(ModParryRiposte.class);
            if (buff == null
                    || !buff.riposteEnabled
                    || !riposter.isAlive()
                    || !attacker.isAlive()) {
                Actor.remove(this);
                return true;
            }

            if (riposter.sprite != null
                    && (riposter.sprite.visible
                    || (attacker.sprite != null && attacker.sprite.visible))) {
                waitingForAnimation = true;
                riposter.sprite.attack(attacker.pos, new Callback() {
                    @Override
                    public void call() {
                        if (!waitingForAnimation) {
                            return;
                        }
                        waitingForAnimation = false;
                        try {
                            performRiposte(riposter, attacker);
                        } finally {
                            // act() returned false, so this Actor is still the
                            // current actor. Release processing only after the
                            // animation callback has finished the counterattack.
                            RiposteActor.this.next();
                            Actor.remove(RiposteActor.this);
                        }
                    }
                });
                return false;
            }

            performRiposte(riposter, attacker);
            Actor.remove(this);
            return true;
        }
    }

    @Override
    public HashSet<Class> immunities() {
        // ChampionEnemy normally grants AllyBuff immunity; Total must not.
        return new HashSet<>();
    }

    @Override
    public HashSet<Class> resistances() {
        return new HashSet<>();
    }

    /**
     * Compatibility shell for saves made by the removed all-character hook
     * implementation. Restored instances refuse attachment and disappear.
     */
    @Deprecated
    public static class CombatHook extends ChampionEnemy {

        private boolean restoredFromBundle;

        {
            revivePersists = false;
        }

        @Override
        public boolean attachTo(Char target) {
            if (restoredFromBundle) {
                restoredFromBundle = false;
                return false;
            }
            // This helper is no longer used at runtime.
            return false;
        }

        @Override
        public void restoreFromBundle(Bundle bundle) {
            super.restoreFromBundle(bundle);
            restoredFromBundle = true;
        }

        @Override
        public int icon() {
            return BuffIndicator.NONE;
        }

        @Override
        public void fx(boolean on) {
        }
    }

    @Deprecated
    public static class AttackWatcher extends CombatHook {
    }
}
