package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ChampionEnemy;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Combo;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.items.KindOfWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sai;
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
        // Char.updateSpriteState() calls fx() while iterating the live buff set,
        // so this method must not attach helper buffs or mutate that collection.
        if (on) {
            ModTotalInfoOverlay.ensureInstalled();
        }
    }

    @Override
    public boolean act() {
        // Total needs no periodic runtime plumbing.
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
                    + "Riposte is ON: every attack parried by Total Parry immediately triggers a guaranteed-hit "
                    + "counterattack, regardless of distance, attempted as a surprise attack. Use the button below to turn riposte off.";
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
        riposteEnabled = bundle.getBoolean(RIPOSTE_ENABLED);
    }

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
            boolean hit;
            Hero hero = riposter instanceof Hero ? (Hero) riposter : null;

            if (hero != null) {
                int originalInvisible = hero.invisible;
                int originalStrength = hero.STR;
                KindOfWeapon attackingWeapon = hero.belongings.attackingWeapon();

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

                    hit = hero.attack(attacker, 1f, 0f, Char.INFINITE_ACCURACY);
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
                    Buff.affect(hero, Sai.ComboStrikeTracker.class).addHit();
                }
            }
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
