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
import java.util.HashSet;

/** Permanent Master Mode combat buff. */
public class ModParryRiposte extends ChampionEnemy {

    private static final String RIPOSTE_ENABLED = "riposte_enabled";

    private static Field currentActorField;

    private boolean riposteEnabled;

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
        // Old saves may contain Total Parry without the hidden Focus bridge.
        ensureParryFocus();
        diactivate();
        return true;
    }

    @Override
    public void detach() {
        if (target != null) {
            for (TotalParryFocus focus : target.buffs(TotalParryFocus.class)) {
                focus.removeForTotalParry();
            }
        }
        super.detach();
        BuffIndicator.refreshHero();
    }

    private void ensureParryFocus() {
        if (!(target instanceof Hero) || !target.isAlive()) {
            return;
        }
        if (target.buffs(TotalParryFocus.class).isEmpty()) {
            new TotalParryFocus().attachTo(target);
        }
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
            return "Permanent Master Mode buff. Total Parry uses the Monk Focus parry path on the Hero, so every incoming attack handled by the normal hit check is parried, including attacks with engine-level infinite accuracy. "
                    + "Riposte is ON: every attack parried by Total Parry immediately triggers a guaranteed-hit counterattack, regardless of distance, attempted as a surprise attack. "
                    + "Enemy Focus and other engine-level INFINITE_EVASION are bypassed before consumable parry/miss hooks can turn that riposte into a miss. Open this buff's information window to turn riposte off.";
        } else {
            return "Permanent Master Mode buff. Total Parry uses the Monk Focus parry path on the Hero, so every incoming attack handled by the normal hit check is parried, including attacks with engine-level infinite accuracy. "
                    + "Riposte is OFF, so the buff only parries. Open this buff's information window to turn riposte on.";
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
        riposteEnabled = bundle.getBoolean(RIPOSTE_ENABLED);
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

        // Normally TotalParryFocus is detected by Char.hit before ChampionEnemy
        // factors are consulted. Keep this as a fallback for unusual forks where
        // the Focus check does not recognize subclasses.
        if (riposteEnabled && attacker.isAlive()) {
            scheduleRiposte(target, attacker);
        }
        return Float.POSITIVE_INFINITY;
    }

    /** Called when Hero.defenseVerb() tries to consume the hidden Focus bridge. */
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
     * Hidden permanent variant of Monk Focus. Char.hit sees this subclass through
     * Char.buff(FocusBuff.class), which gives the Hero INFINITE_EVASION before the
     * engine evaluates INFINITE_ACCURACY. Hero.defenseVerb() then calls detach(),
     * plays the native HIT_PARRY sound and displays the native Monk parry verb.
     * We intercept that detach so the Focus remains for the next incoming attack.
     */
    public static class TotalParryFocus extends MonkEnergy.MonkAbility.Focus.FocusBuff {

        private boolean forceDetach;

        {
            revivePersists = true;
        }

        @Override
        public void detach() {
            ModParryRiposte total = ModParryRiposte.find(target);
            if (!forceDetach && total != null && target != null && target.isAlive()) {
                total.onFocusParry();
                return;
            }
            super.detach();
        }

        void removeForTotalParry() {
            forceDetach = true;
            super.detach();
        }

        @Override
        public int icon() {
            return BuffIndicator.NONE;
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
