package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ChampionEnemy;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MonkEnergy;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.spd.mod.journal.ModTotalInfoOverlay;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;

import java.util.HashSet;

/**
 * Permanent Master Mode combat buff.
 *
 * Total always parries normal hit-checked attacks. Riposte is an independent
 * per-buff switch: when enabled, every attack intercepted by Total immediately
 * schedules a guaranteed normal counterattack. Counterattacks deliberately do
 * not perform a range check.
 *
 * This class does not modify vanilla SPD source. CombatHook uses the generic
 * ChampionEnemy accuracy/evasion callback already present in Char.hit().
 */
public class ModParryRiposte extends Buff {

    private static final String RIPOSTE_ENABLED = "riposte_enabled";

    // Keys used by the previous implementation; read only for save migration.
    private static final String LEGACY_MODE = "mode";
    private static final String LEGACY_OWNS_FOCUS = "owns_focus";

    private boolean riposteEnabled;
    private boolean removeLegacyFocus;

    private static HookKeeper hookKeeper;

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
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
        ensureInfrastructure();
        return true;
    }

    @Override
    public boolean act() {
        // Clean up the hidden Focus left by the previous PARRY implementation
        // only when that old save explicitly says this buff owned it.
        if (removeLegacyFocus && target != null) {
            MonkEnergy.MonkAbility.Focus.FocusBuff focus =
                    target.buff(MonkEnergy.MonkAbility.Focus.FocusBuff.class);
            if (focus != null) {
                focus.detach();
            }
            removeLegacyFocus = false;
        }

        ensureInfrastructure();
        spend(TICK);
        return true;
    }

    @Override
    public void detach() {
        super.detach();
        cleanupInfrastructureIfUnused();
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
        return "Total";
    }

    @Override
    public String desc() {
        if (riposteEnabled) {
            return "Permanent Master Mode buff. Total always parries incoming attacks handled by the normal hit check. "
                    + "Riposte is ON: every attack parried by Total immediately triggers a guaranteed normal "
                    + "counterattack, regardless of distance. Use the button below to turn riposte off.";
        } else {
            return "Permanent Master Mode buff. Total always parries incoming attacks handled by the normal hit check. "
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
            // Old RIPOSTE meant "counterattack mode". Migrate it to Total with
            // riposte enabled; old PARRY becomes Total with riposte disabled.
            riposteEnabled = "RIPOSTE".equals(bundle.getString(LEGACY_MODE));
        }

        removeLegacyFocus = bundle.contains(LEGACY_OWNS_FOCUS)
                && bundle.getBoolean(LEGACY_OWNS_FOCUS);
    }

    private static boolean hasTotalUser() {
        for (Char ch : Actor.chars()) {
            if (ch.buff(ModParryRiposte.class) != null) {
                return true;
            }
        }
        return false;
    }

    private static void ensureInfrastructure() {
        ensureCombatHooks();
        ensureHookKeeper();
        ModTotalInfoOverlay.ensureInstalled();
    }

    public static void ensureCombatHooks() {
        if (!hasTotalUser()) {
            return;
        }
        for (Char ch : Actor.chars()) {
            if (ch.buff(CombatHook.class) == null) {
                CombatHook.attachRuntime(ch);
            }
        }
    }

    private static void ensureHookKeeper() {
        if (hookKeeper == null || !hookKeeper.exists || !Actor.all().contains(hookKeeper)) {
            hookKeeper = new HookKeeper();
            Actor.add(hookKeeper);
        }
    }

    private static void cleanupInfrastructureIfUnused() {
        if (hasTotalUser()) {
            return;
        }

        for (Char ch : Actor.chars()) {
            Buff.detach(ch, CombatHook.class);
        }

        CombatHook.resetPairing();

        if (hookKeeper != null) {
            Actor.remove(hookKeeper);
            hookKeeper = null;
        }
    }

    private static void scheduleRiposte(final Char riposter, final Char attacker) {
        Actor.add(new Actor() {
            {
                actPriority = VFX_PRIO;
            }

            @Override
            protected boolean act() {
                ModParryRiposte buff = riposter.buff(ModParryRiposte.class);
                if (buff != null
                        && buff.riposteEnabled
                        && riposter.isAlive()
                        && attacker.isAlive()) {
                    if (riposter.sprite != null) {
                        riposter.sprite.attack(attacker.pos);
                    }
                    // Intentionally no canAttack/range check. "Total Riposte"
                    // must always be able to answer an attack that it parried.
                    riposter.attack(attacker, 1f, 0f, Char.INFINITE_ACCURACY);
                }

                Actor.remove(this);
                return true;
            }
        });
    }

    /**
     * One lightweight keeper is enough to attach CombatHook to newly-added
     * characters before normal actor turns at the same timestamp.
     */
    private static class HookKeeper extends Actor {

        {
            actPriority = VFX_PRIO;
        }

        @Override
        protected boolean act() {
            if (!hasTotalUser()) {
                for (Char ch : Actor.chars()) {
                    Buff.detach(ch, CombatHook.class);
                }
                CombatHook.resetPairing();
                Actor.remove(this);
                if (hookKeeper == this) {
                    hookKeeper = null;
                }
                return true;
            }

            ensureCombatHooks();
            ModTotalInfoOverlay.ensureInstalled();
            spend(TICK);
            return true;
        }
    }

    /**
     * Runtime-only hook attached to every active Char while any Total buff is
     * present. Char.hit() evaluates ChampionEnemy accuracy/evasion modifiers
     * for the attacker first and defender second, so two consecutive calls let
     * us identify the attack pair without modifying Char.java.
     */
    public static class CombatHook extends ChampionEnemy {

        private static Char pendingAttacker;
        private static boolean waitingForDefender;

        private boolean restoredFromBundle;

        {
            revivePersists = false;
        }

        static void attachRuntime(Char target) {
            CombatHook hook = new CombatHook();
            hook.attachTo(target);
        }

        static void resetPairing() {
            pendingAttacker = null;
            waitingForDefender = false;
        }

        @Override
        public boolean attachTo(Char target) {
            // Saved hook objects are implementation plumbing and must not be
            // restored. The visible Total buff recreates fresh hooks instead.
            if (restoredFromBundle) {
                restoredFromBundle = false;
                return false;
            }
            return super.attachTo(target);
        }

        @Override
        public void restoreFromBundle(Bundle bundle) {
            super.restoreFromBundle(bundle);
            restoredFromBundle = true;
        }

        @Override
        public float evasionAndAccuracyFactor() {
            if (!waitingForDefender) {
                pendingAttacker = target;
                waitingForDefender = true;
                return 1f;
            }

            Char attacker = pendingAttacker;
            pendingAttacker = null;
            waitingForDefender = false;

            ModParryRiposte total = target == null
                    ? null
                    : target.buff(ModParryRiposte.class);

            if (total != null) {
                if (total.riposteEnabled
                        && attacker != null
                        && attacker != target
                        && attacker.isAlive()
                        && target.isAlive()) {
                    scheduleRiposte(target, attacker);
                }

                // For positive defense rolls this becomes infinite evasion.
                // For a zero defense roll Java produces NaN (0 * Infinity),
                // and Char.hit's >= comparison is still false, so the attack
                // is also parried. This keeps Total applicable to any Char.
                return Float.POSITIVE_INFINITY;
            }

            return 1f;
        }

        @Override
        public int icon() {
            return BuffIndicator.NONE;
        }

        @Override
        public String name() {
            return "\u200B";
        }

        @Override
        public String desc() {
            return "";
        }

        @Override
        public void fx(boolean on) {
            // No champion aura. This is an invisible implementation hook.
        }

        @Override
        public HashSet<Class> immunities() {
            return new HashSet<>();
        }

        @Override
        public HashSet<Class> resistances() {
            return new HashSet<>();
        }

        @Override
        public boolean act() {
            diactivate();
            return true;
        }
    }

    /**
     * Compatibility shell for saves produced by the two earlier broken
     * implementations. CombatHook's restore guard makes restored instances
     * refuse attachment, so this class has no runtime behavior.
     */
    @Deprecated
    public static class AttackWatcher extends CombatHook {
    }
}
