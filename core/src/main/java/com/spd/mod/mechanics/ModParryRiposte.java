package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ChampionEnemy;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Combo;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MonkEnergy;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.spd.mod.journal.ModTotalInfoOverlay;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.WeakHashMap;

/** Permanent Master Mode combat buff with independent Parry and Riposte controls. */
public class ModParryRiposte extends ChampionEnemy {

    private static final String PARRY_ENABLED = "parry_enabled";
    private static final String RIPOSTE_ENABLED = "riposte_enabled";
    private static final String OWNS_PARRY_FOCUS = "owns_parry_focus";

    private static Field currentActorField;

    /*
     * Char.buff(Class) deliberately matches exact classes, not subclasses. Total
     * Parry therefore has to keep a real Monk FocusBuff in the Hero's buff set.
     * Hero.defenseVerb() tries to consume that Focus by calling detach(), whose
     * implementation removes the buff from buff.target. We point the helper's
     * target at this out-of-world sink instead, so the exact Focus remains in the
     * real Hero's buff set and the native parry path can be reused indefinitely.
     */
    private static final ParryDetachSink PARRY_DETACH_SINK = new ParryDetachSink();
    private static final Map<Buff, ModParryRiposte> PARRY_FOCUS_OWNERS =
            Collections.synchronizedMap(new WeakHashMap<Buff, ModParryRiposte>());

    /*
     * The Char.attack() hook and Char.hit()-based fallback paths can observe the
     * same normal attack. Keep at most one queued riposte per attacker until that
     * counterattack runs, so ordinary attacks still produce exactly one Riposte
     * while direct Char.hit() special attacks remain covered.
     */
    private static final Map<Char, RiposteActor> PENDING_RIPOSTES =
            Collections.synchronizedMap(new WeakHashMap<Char, RiposteActor>());

    private boolean parryEnabled = true;
    private boolean riposteEnabled;
    private boolean ownsParryFocus;
    private boolean restoringFromBundle;

    {
        announced = true;
        revivePersists = true;
        actPriority = VFX_PRIO;
        color = 0xFFFFFF;
    }

    /** Stable across supported SPD forks; avoids Char.buff(Class) ABI variance. */
    public static ModParryRiposte find(Char ch) {
        if (ch == null) {
            return null;
        }
        for (ModParryRiposte buff : ch.buffs(ModParryRiposte.class)) {
            return buff;
        }
        return null;
    }

    /** Returns only an exact native Monk FocusBuff, never a subclass. */
    private static MonkEnergy.MonkAbility.Focus.FocusBuff findExactFocus(Char ch) {
        if (ch == null) {
            return null;
        }
        for (MonkEnergy.MonkAbility.Focus.FocusBuff focus
                : ch.buffs(MonkEnergy.MonkAbility.Focus.FocusBuff.class)) {
            if (focus != null
                    && focus.getClass() == MonkEnergy.MonkAbility.Focus.FocusBuff.class) {
                return focus;
            }
        }
        return null;
    }

    /** True only for an exact native Focus instance currently maintained by Total Parry. */
    public static boolean isParryFocus(Buff buff) {
        return buff != null && PARRY_FOCUS_OWNERS.containsKey(buff);
    }

    public boolean parryEnabled() {
        return parryEnabled;
    }

    public void setParryEnabled(boolean enabled) {
        if (parryEnabled == enabled) {
            return;
        }
        parryEnabled = enabled;
        if (enabled) {
            ensureParryFocus();
        } else {
            detachAllExactFocus();
        }
        BuffIndicator.refreshHero();
    }

    public void toggleParry() {
        setParryEnabled(!parryEnabled);
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

    /**
     * Pre-resolution incoming-attack hook. Riposte is intentionally independent
     * from Parry, hit/miss resolution, and defender invulnerability. The hook only
     * queues the counterattack; it never executes it inline with the incoming
     * attack, so the triggering action can finish first.
     */
    public static void onIncomingAttack(Char attacker, Char defender) {
        if (!(defender instanceof Hero)
                || attacker == null
                || attacker == defender
                || !attacker.isAlive()
                || !defender.isAlive()) {
            return;
        }

        ModParryRiposte total = find(defender);
        if (total != null && total.riposteEnabled) {
            scheduleRiposte(defender, attacker);
        }
    }

    @Override
    public boolean attachTo(Char target) {
        if (!super.attachTo(target)) {
            return false;
        }
        timeToNow();
        // Saved buffs are restored one-by-one. If this buff restores before its
        // saved Focus helper, creating one here would add another Focus every load.
        if (!restoringFromBundle && parryEnabled) {
            ensureParryFocus();
        }
        ModTotalInfoOverlay.ensureInstalled();
        return true;
    }

    @Override
    public void fx(boolean on) {
        // Char.updateSpriteState() calls fx() while iterating the live buff set,
        // so this method must not attach helper buffs or mutate that collection.
        if (on) {
            ModTotalInfoOverlay.ensureInstalled();
        }
    }

    @Override
    public boolean act() {
        // By the time Actor processing resumes, Char.restoreFromBundle() has
        // attached every saved buff. Reuse/rebind the restored Focus only when
        // Parry is enabled. Parry OFF means no exact Focus may remain.
        restoringFromBundle = false;
        if (parryEnabled) {
            ensureParryFocus();
        } else {
            detachAllExactFocus();
        }
        diactivate();
        return true;
    }

    @Override
    public void detach() {
        releaseParryFocus();
        super.detach();
        BuffIndicator.refreshHero();
    }

    private void ensureParryFocus() {
        if (!parryEnabled || !(target instanceof Hero) || !target.isAlive()) {
            return;
        }

        MonkEnergy.MonkAbility.Focus.FocusBuff focus = findExactFocus(target);

        if (focus == null) {
            focus = new MonkEnergy.MonkAbility.Focus.FocusBuff();
            focus.announced = false;
            focus.revivePersists = true;
            if (!focus.attachTo(target)) {
                return;
            }
            ownsParryFocus = true;
        }

        focus.announced = false;
        focus.revivePersists = true;
        PARRY_FOCUS_OWNERS.put(focus, this);
        focus.target = PARRY_DETACH_SINK;
    }

    /**
     * Parry OFF is deliberately stronger than releaseParryFocus(): the user asked
     * for every exact native Focus on the Hero to be detached, including a real
     * Monk Focus that Total Parry may have borrowed.
     */
    private void detachAllExactFocus() {
        if (!(target instanceof Hero)) {
            ownsParryFocus = false;
            return;
        }

        ArrayList<MonkEnergy.MonkAbility.Focus.FocusBuff> focuses = new ArrayList<>();
        for (MonkEnergy.MonkAbility.Focus.FocusBuff focus
                : target.buffs(MonkEnergy.MonkAbility.Focus.FocusBuff.class)) {
            if (focus != null
                    && focus.getClass() == MonkEnergy.MonkAbility.Focus.FocusBuff.class) {
                focuses.add(focus);
            }
        }

        for (MonkEnergy.MonkAbility.Focus.FocusBuff focus : focuses) {
            PARRY_FOCUS_OWNERS.remove(focus);
            focus.target = target;
            focus.detach();
        }
        ownsParryFocus = false;
    }

    /** Used only when the Total buff itself is removed; borrowed natural Focus survives. */
    private void releaseParryFocus() {
        if (!(target instanceof Hero)) {
            return;
        }

        MonkEnergy.MonkAbility.Focus.FocusBuff focus = findExactFocus(target);
        if (focus == null || PARRY_FOCUS_OWNERS.get(focus) != this) {
            return;
        }

        PARRY_FOCUS_OWNERS.remove(focus);
        if (ownsParryFocus) {
            // Remove from the actual Hero collection directly; focus.target points
            // at the detach sink while Total Parry owns it.
            focus.target = target;
            focus.detach();
        } else {
            // A real Monk Focus that existed before Total Parry was attached is
            // borrowed rather than destroyed when the Total buff itself goes away.
            focus.target = target;
        }
        ownsParryFocus = false;
    }

    @Override
    public int icon() {
        return ModBuffIconCompat.get("DUEL_GUARD");
    }

    @Override
    public void tintIcon(Image icon) {
        if (riposteEnabled) {
            icon.hardlight(0xB06CFF);
        } else if (parryEnabled) {
            icon.hardlight(0x55CCFF);
        } else {
            icon.hardlight(0xAAAAAA);
        }
    }

    @Override
    public String iconTextDisplay() {
        if (parryEnabled && riposteEnabled) {
            return "PR";
        } else if (parryEnabled) {
            return "P";
        } else if (riposteEnabled) {
            return "R";
        } else {
            return "-";
        }
    }

    @Override
    public String name() {
        return "Total Parry / Riposte";
    }

    @Override
    public String desc() {
        return "Parry: " + (parryEnabled ? "ON" : "OFF")
                + ". When ON, incoming hit checks are parried. Riposte: "
                + (riposteEnabled ? "ON" : "OFF")
                + ". When ON, incoming attacks trigger a counterattack using normal hit rules. "
                + "Force Hit overrides the Riposte hit check when that buff is active. Tap to configure.";
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(PARRY_ENABLED, parryEnabled);
        bundle.put(RIPOSTE_ENABLED, riposteEnabled);
        bundle.put(OWNS_PARRY_FOCUS, ownsParryFocus);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        // Bundle collection restoration constructs/restores each buff before Char
        // attaches it. Mark this instance so attachTo() waits for sibling Focus.
        restoringFromBundle = true;
        super.restoreFromBundle(bundle);
        // Old saves predate this switch and must preserve the historical ON state.
        parryEnabled = !bundle.contains(PARRY_ENABLED) || bundle.getBoolean(PARRY_ENABLED);
        riposteEnabled = bundle.getBoolean(RIPOSTE_ENABLED);
        ownsParryFocus = bundle.getBoolean(OWNS_PARRY_FOCUS);
    }

    @Override
    public float evasionAndAccuracyFactor() {
        Char attacker = currentAttackSource();

        // Direct Char.hit() special attacks do not pass through the injected
        // Char.attack() hook. When no exact Focus short-circuits Char.hit(), this
        // defender-factor callback supplies the missing Riposte trigger. Pending
        // Ripostes deduplicate it against an ordinary Char.attack() observation.
        if (riposteEnabled
                && target instanceof Hero
                && target.isAlive()
                && attacker != null
                && attacker != target
                && attacker.isAlive()) {
            scheduleRiposte(target, attacker);
        }

        if (!parryEnabled) {
            return 1f;
        }

        // If Total's owner is the current attack source, this is the attacker's
        // accuracy pass. Total Parry must never modify its own outgoing accuracy.
        if (attacker == null || attacker == target) {
            return 1f;
        }

        if (target == null || !target.isAlive()) {
            return 1f;
        }

        // Exact native Focus normally resolves the attack before ChampionEnemy
        // factors are consulted. This remains only a defensive Parry fallback for
        // forks or transient restore states where Focus has not yet been rebound.
        return Float.POSITIVE_INFINITY;
    }

    /** Called when native Hero.defenseVerb() consumes Total's exact Focus helper. */
    private void onFocusParry() {
        if (!riposteEnabled || target == null || !target.isAlive()) {
            return;
        }
        Char attacker = currentAttackSource();
        if (attacker != null && attacker != target && attacker.isAlive()) {
            scheduleRiposte(target, attacker);
        }
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
            return null;
        }
    }

    private static void scheduleRiposte(Char riposter, Char attacker) {
        synchronized (PENDING_RIPOSTES) {
            RiposteActor pending = PENDING_RIPOSTES.get(attacker);
            if (pending != null && pending.riposter == riposter) {
                return;
            }

            RiposteActor actor = new RiposteActor(riposter, attacker);
            PENDING_RIPOSTES.put(attacker, actor);
            Actor.add(actor);
        }
    }

    private static void clearPendingRiposte(RiposteActor actor) {
        synchronized (PENDING_RIPOSTES) {
            if (PENDING_RIPOSTES.get(actor.attacker) == actor) {
                PENDING_RIPOSTES.remove(actor.attacker);
            }
        }
    }

    private static void performRiposte(Char riposter, Char attacker) {
        ModParryRiposte buff = find(riposter);
        if (buff != null
                && buff.riposteEnabled
                && riposter.isAlive()
                && attacker.isAlive()) {
            // Intentionally no canAttack/range check. Total Riposte answers the
            // incoming attack regardless of distance or Parry state.
            boolean hit;
            Hero hero = riposter instanceof Hero ? (Hero) riposter : null;

            if (hero != null) {
                boolean forceHit = ModForceHit.find(hero) != null;
                boolean bypassInfiniteEvasion = forceHit
                        && !attacker.isInvulnerable(hero.getClass())
                        && ModCombatCompat.hasInfiniteEvasionAgainst(attacker, hero);

                // Riposte is a normal hit check. Force Hit is the only Mod feature
                // that upgrades it to guaranteed accuracy.
                hit = bypassInfiniteEvasion
                        ? ModCombatCompat.forceHeroHit(hero, attacker, 1f, 0f)
                        : hero.attack(attacker, 1f, 0f,
                                forceHit ? Char.INFINITE_ACCURACY : 1f);
            } else {
                hit = riposter.attack(attacker, 1f, 0f, 1f);
            }

            // Direct Char.attack() calls bypass Hero.onAttackComplete(), so mirror
            // the hit counters that normal hero attacks update there.
            if (hit && hero != null) {
                if (hero.subClass == HeroSubClass.GLADIATOR) {
                    Buff.affect(hero, Combo.class).hit(attacker);
                }
                if (hero.heroClass == HeroClass.DUELIST) {
                    ModCombatCompat.addDuelistComboHit(hero, attacker);
                }
            }
        }
    }

    /**
     * Out-of-world target for the exact native Focus helper. FocusBuff.detach()
     * calls target.remove(this); redirecting that call here leaves the same exact
     * Focus object inside the real Hero's buff set. The callback also reports a
     * native Focus parry to Riposte so direct Char.hit() attacks remain observable.
     */
    private static class ParryDetachSink extends Hero {
        @Override
        public synchronized boolean remove(Buff buff) {
            ModParryRiposte total = PARRY_FOCUS_OWNERS.get(buff);
            if (total != null) {
                total.onFocusParry();
                Actor.remove(buff);
                return true;
            }
            return super.remove(buff);
        }
    }

    /** Holds Actor processing until a visible riposte animation completes. */
    private static class RiposteActor extends Actor {

        private final Char riposter;
        private final Char attacker;
        private boolean waitingForAnimation;

        RiposteActor(Char riposter, Char attacker) {
            this.riposter = riposter;
            this.attacker = attacker;
            actPriority = VFX_PRIO;
        }

        private void removeSelf() {
            clearPendingRiposte(this);
            Actor.remove(this);
        }

        @Override
        protected boolean act() {
            ModParryRiposte buff = find(riposter);
            if (buff == null
                    || !buff.riposteEnabled
                    || !riposter.isAlive()
                    || !attacker.isAlive()) {
                removeSelf();
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
                            RiposteActor.this.next();
                            RiposteActor.this.removeSelf();
                        }
                    }
                });
                return false;
            }

            try {
                performRiposte(riposter, attacker);
            } finally {
                removeSelf();
            }
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
}
