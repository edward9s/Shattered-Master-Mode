package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Monk;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.spd.mod.journal.WndTotalBuffInfo;
import com.watabou.noosa.Image;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;
import com.watabou.utils.Random;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Permanent combat buff with independent Parry and Riposte controls. */
public class ModParryRiposte extends Buff {

    private static final String PARRY_ENABLED = "parry_enabled";
    private static final String RIPOSTE_ENABLED = "riposte_enabled";

    private static final ThreadLocal<Map<Char, Boolean>> PARRY_FEEDBACK_TARGETS =
            new ThreadLocal<>();

    /*
     * One pending Riposte per attacker is enough to deduplicate repeated hit()
     * observations that happen synchronously inside one attack.
     */
    private static final Map<Char, RiposteActor> PENDING_RIPOSTES =
            Collections.synchronizedMap(new WeakHashMap<Char, RiposteActor>());

    private boolean parryEnabled = true;
    private boolean riposteEnabled = true;

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
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

    private static Map<Char, Boolean> parryFeedbackTargets() {
        Map<Char, Boolean> targets = PARRY_FEEDBACK_TARGETS.get();
        if (targets == null) {
            targets = new WeakHashMap<>();
            PARRY_FEEDBACK_TARGETS.set(targets);
        }
        return targets;
    }

    public boolean parryEnabled() {
        return parryEnabled;
    }

    public void setParryEnabled(boolean enabled) {
        if (parryEnabled == enabled) {
            return;
        }
        parryEnabled = enabled;
        BuffIndicator.refreshHero();
    }

    public void toggleParry() {
        setParryEnabled(!parryEnabled);
    }

    public boolean riposteEnabled() {
        return riposteEnabled;
    }

    public void setRiposteEnabled(boolean enabled) {
        if (riposteEnabled == enabled) {
            return;
        }
        riposteEnabled = enabled;
        BuffIndicator.refreshHero();
    }

    public void toggleRiposte() {
        setRiposteEnabled(!riposteEnabled);
    }

    public void openInfo() {
        if (target != null && find(target) == this) {
            GameScene.show(new WndTotalBuffInfo(this));
        }
    }

    /**
     * Single combat observation point for every supported SPD hit check.
     *
     * Riposte is queued as soon as an incoming hit check is observed, regardless
     * of range and independently of whether Force Hit later overrides Parry.
     * Returning true requests that this hit check resolve as a miss.
     */
    public static boolean onHitCheck(Char attacker, Char defender) {
        if (defender != null) {
            Map<Char, Boolean> targets = PARRY_FEEDBACK_TARGETS.get();
            if (targets != null) {
                targets.remove(defender);
                if (targets.isEmpty()) {
                    PARRY_FEEDBACK_TARGETS.remove();
                }
            }
        }

        if (attacker == null
                || defender == null
                || attacker == defender
                || !attacker.isAlive()
                || !defender.isAlive()) {
            return false;
        }

        ModParryRiposte buff = find(defender);
        if (buff == null || buff.target != defender) {
            return false;
        }

        if (buff.riposteEnabled) {
            queueRiposte(defender, attacker);
        }

        if (!buff.parryEnabled) {
            return false;
        }

        parryFeedbackTargets().put(defender, Boolean.TRUE);
        return true;
    }

    /**
     * Presentation-only bridge. Combat resolution has already happened in
     * onHitCheck(); this only replaces the next defense message/sound for the
     * matching defender.
     */
    public static String defenseVerb(Char defender) {
        Map<Char, Boolean> targets = PARRY_FEEDBACK_TARGETS.get();
        if (targets == null || targets.remove(defender) == null) {
            return defender.defenseVerb();
        }
        if (targets.isEmpty()) {
            PARRY_FEEDBACK_TARGETS.remove();
        }

        if (defender.sprite != null && defender.sprite.visible) {
            Sample.INSTANCE.play(
                    Assets.Sounds.HIT_PARRY,
                    1,
                    Random.Float(0.96f, 1.05f));
        }
        return Messages.get(Monk.class, "parried");
    }

    @Override
    public boolean act() {
        spend(TICK);
        return true;
    }

    @Override
    public void detach() {
        super.detach();
        BuffIndicator.refreshHero();
    }

    @Override
    public int icon() {
        return ModBuffIconCompat.getFirst(
                "DUEL_GUARD",
                "ARMOR",
                "HEART");
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
        return "Parry/Riposte";
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public String desc() {
        return "Parry: " + (parryEnabled ? "ON" : "OFF")
                + ". When ON, incoming hit checks are forced to miss. Riposte: "
                + (riposteEnabled ? "ON" : "OFF")
                + ". When ON, every incoming hit check triggers a range-independent counterattack. "
                + "Force Hit overrides Parry but does not suppress Riposte. Tap to configure.";
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(PARRY_ENABLED, parryEnabled);
        bundle.put(RIPOSTE_ENABLED, riposteEnabled);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        parryEnabled = !bundle.contains(PARRY_ENABLED) || bundle.getBoolean(PARRY_ENABLED);
        riposteEnabled = !bundle.contains(RIPOSTE_ENABLED) || bundle.getBoolean(RIPOSTE_ENABLED);
    }

    private static void queueRiposte(Char riposter, Char attacker) {
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
            boolean hit = ModCombatCompat.performRiposteAttack(riposter, attacker);
            if (hit) {
                ModCombatCompat.recordRiposteHit(riposter, attacker);
            }
        }
    }

    /** Holds Actor processing until a visible Riposte animation completes. */
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
                        }
                    }
                });
                removeSelf();
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
}
