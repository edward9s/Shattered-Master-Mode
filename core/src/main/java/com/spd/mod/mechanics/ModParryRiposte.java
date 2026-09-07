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
import com.shatteredpixel.shatteredpixeldungeon.items.KindOfWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.spd.mod.journal.ModTotalInfoOverlay;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.WeakHashMap;

/** Permanent Master Mode combat buff. */
public class ModParryRiposte extends ChampionEnemy {

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

    private boolean riposteEnabled;
    private boolean ownsParryFocus;

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

    /** True only for the exact native Focus instance maintained by Total Parry. */
    public static boolean isParryFocus(Buff buff) {
        return buff != null && PARRY_FOCUS_OWNERS.containsKey(buff);
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
        ensureParryFocus();
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
        // Rebind restored Focus instances to the detach sink after save loading.
        ensureParryFocus();
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
        if (!(target instanceof Hero) || !target.isAlive()) {
            return;
        }

        MonkEnergy.MonkAbility.Focus.FocusBuff focus =
                target.buff(MonkEnergy.MonkAbility.Focus.FocusBuff.class);

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

    private void releaseParryFocus() {
        if (!(target instanceof Hero)) {
            return;
        }

        MonkEnergy.MonkAbility.Focus.FocusBuff focus =
                target.buff(MonkEnergy.MonkAbility.Focus.FocusBuff.class);
        if (focus == null || PARRY_FOCUS_OWNERS.get(focus) != this) {
            return;
        }

        PARRY_FOCUS_OWNERS.remove(focus);
        if (ownsParryFocus) {
            // Remove from the actual Hero collection directly; focus.target points
            // at the detach sink while Total Parry owns it.
            target.remove(focus);
        } else {
            // A real Monk Focus that existed before Total Parry was attached is
            // borrowed rather than destroyed. Restore normal detach semantics.
            focus.target = target;
        }
        ownsParryFocus = false;
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
            return "Permanent Master Mode buff. Total Parry keeps the Hero on the native Monk Focus parry path, so every incoming attack handled by the normal hit check is parried, including attacks with engine-level infinite accuracy. "
                    + "The native Monk parry feedback is used rather than an ordinary dodge. Riposte is ON: every attack parried by Total Parry immediately triggers a guaranteed-hit counterattack, regardless of distance, attempted as a surprise attack. "
                    + "Enemy Focus and other engine-level INFINITE_EVASION are bypassed before consumable parry/miss hooks can turn that riposte into a miss. Open this buff's information window to turn riposte off.";
        } else {
            return "Permanent Master Mode buff. Total Parry keeps the Hero on the native Monk Focus parry path, so every incoming attack handled by the normal hit check is parried, including attacks with engine-level infinite accuracy. "
                    + "The native Monk parry feedback is used rather than an ordinary dodge. Riposte is OFF, so the buff only parries. Open this buff's information window to turn riposte on.";
        }
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(RIPOSTE_ENABLED, riposteEnabled);
        bundle.put(OWNS_PARRY_FOCUS, ownsParryFocus);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        riposteEnabled = bundle.getBoolean(RIPOSTE_ENABLED);
        ownsParryFocus = bundle.getBoolean(OWNS_PARRY_FOCUS);
    }

    @Override
    public float evasionAndAccuracyFactor() {
        Char attacker = currentAttackSource();

        // If Total's owner is the current attack source, this is the attacker's
        // accuracy pass. Total must never modify its own outgoing accuracy.
        if (attacker == null || attacker == target) {
            return 1f;
        }

        if (target == null || !target.isAlive()) {
            return 1f;
        }

        // Exact native Focus normally resolves the attack before ChampionEnemy
        // factors are consulted. This remains a defensive fallback for forks or
        // transient restore states where Focus has not yet been rebound.
        if (riposteEnabled && attacker.isAlive()) {
            scheduleRiposte(target, attacker);
        }
        return Float.POSITIVE_INFINITY;
    }

    /** Called by the detach sink when native Hero.defenseVerb() consumes Focus. */
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
        Actor.add(new RiposteActor(riposter, attacker));
    }

    private static void performRiposte(Char riposter, Char attacker) {
        ModParryRiposte buff = find(riposter);
        if (buff != null
                && buff.riposteEnabled
                && riposter.isAlive()
                && attacker.isAlive()) {
            // Intentionally no canAttack/range check. Total Riposte must always
            // be able to answer an attack that Total Parry intercepted.
            boolean hit;
            Hero hero = riposter instanceof Hero ? (Hero) riposter : null;

            if (hero != null) {
                int originalInvisible = hero.invisible;
                int originalStrength = hero.STR;
                KindOfWeapon attackingWeapon = hero.belongings.attackingWeapon();
                boolean bypassInfiniteEvasion = !attacker.isInvulnerable(hero.getClass())
                        && ModCombatCompat.hasInfiniteEvasionAgainst(attacker, hero);

                try {
                    // Mirror ModAssassin: make the riposte qualify for the normal
                    // surprise-attack path without bypassing weapon-specific rules
                    // such as Flail's "cannot surprise attack" restriction.
                    hero.invisible = 1;
                    if (attackingWeapon instanceof Weapon) {
                        int strengthShortfall = ((Weapon) attackingWeapon).STRReq() - hero.STR();
                        if (strengthShortfall > 0) {
                            hero.STR += strengthShortfall;
                        }
                    }

                    // Outgoing ripostes remain absolutely accurate. Enemy Focus is
                    // an enemy-side defense and must not override this Mod feature.
                    hit = bypassInfiniteEvasion
                            ? ModCombatCompat.forceHeroHit(hero, attacker, 1f, 0f)
                            : hero.attack(attacker, 1f, 0f, Char.INFINITE_ACCURACY);
                } finally {
                    hero.invisible = originalInvisible;
                    hero.STR = originalStrength;
                }
            } else {
                hit = riposter.attack(attacker, 1f, 0f, Char.INFINITE_ACCURACY);
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
     * Focus object inside the real Hero's buff set while still notifying Total
     * Riposte that a native parry occurred.
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

        @Override
        protected boolean act() {
            ModParryRiposte buff = find(riposter);
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
}
