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

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Permanent combat buff with independent Parry and Riposte controls. */
public class ModParryRiposte extends Buff {

    private static final String PARRY_ENABLED = "parry_enabled";
    private static final String RIPOSTE_ENABLED = "riposte_enabled";

    private static final ThreadLocal<ArrayDeque<IncomingAttackContext>> INCOMING_ATTACK_CONTEXTS =
            new ThreadLocal<>();
    private static final ThreadLocal<Char> PARRY_FEEDBACK_TARGET =
            new ThreadLocal<>();

    private static final class IncomingAttackContext {
        final Char attacker;
        final Char defender;

        IncomingAttackContext(Char attacker, Char defender) {
            this.attacker = attacker;
            this.defender = defender;
        }
    }

    private static ArrayDeque<IncomingAttackContext> incomingAttackContexts() {
        ArrayDeque<IncomingAttackContext> contexts = INCOMING_ATTACK_CONTEXTS.get();
        if (contexts == null) {
            contexts = new ArrayDeque<>();
            INCOMING_ATTACK_CONTEXTS.set(contexts);
        }
        return contexts;
    }

    /*
     * Char.attack entry/return hooks observe one normal attack. Keep one prepared
     * Riposte per attacker until the completion hook schedules it.
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
     * Captures one incoming Char.attack and prepares its optional Riposte.
     * Parry itself is resolved by shouldParry() from the selected Char.hit hook.
     */
    public static void onIncomingAttack(Char attacker, Char defender) {
        incomingAttackContexts().push(new IncomingAttackContext(attacker, defender));

        if (attacker == null
                || defender == null
                || attacker == defender
                || !attacker.isAlive()
                || !defender.isAlive()) {
            return;
        }

        ModParryRiposte buff = find(defender);
        if (buff != null && buff.riposteEnabled) {
            prepareRiposte(defender, attacker);
        }
    }

    /**
     * Selected Char.hit calls this after Force Hit's early-success check.
     * Returning true means the native hit result is forced to miss.
     */
    public static boolean shouldParry(Char attacker, Char defender) {
        // Every hit check starts a fresh feedback decision. This prevents a
        // caller that does not render defenseVerb() from leaking stale Parry UI
        // into a later miss.
        PARRY_FEEDBACK_TARGET.remove();

        if (attacker == null
                || defender == null
                || attacker == defender
                || !attacker.isAlive()
                || !defender.isAlive()) {
            return false;
        }

        ModParryRiposte buff = find(defender);
        boolean parried = buff != null
                && buff.target == defender
                && buff.parryEnabled;
        if (parried) {
            PARRY_FEEDBACK_TARGET.set(defender);
        }
        return parried;
    }

    /**
     * Central defense-feedback bridge for every Char. Injected/source call sites
     * use this instead of calling defender.defenseVerb() directly.
     *
     * A successful SMM Parry consumes its one-shot marker and returns the same
     * localized text/sound used by Monk parry/focus. Every non-SMM miss keeps
     * the defender's native virtual defenseVerb() behavior unchanged.
     */
    public static String defenseVerb(Char defender) {
        Char pending = PARRY_FEEDBACK_TARGET.get();
        if (pending != defender) {
            return defender.defenseVerb();
        }
        PARRY_FEEDBACK_TARGET.remove();

        if (defender.sprite != null && defender.sprite.visible) {
            Sample.INSTANCE.play(
                    Assets.Sounds.HIT_PARRY,
                    1,
                    Random.Float(0.96f, 1.05f));
        }
        return Messages.get(Monk.class, "parried");
    }

    /** Schedules the prepared Riposte after terminal Char.attack has completed. */
    public static void onIncomingAttackComplete() {
        ArrayDeque<IncomingAttackContext> contexts = INCOMING_ATTACK_CONTEXTS.get();
        if (contexts == null || contexts.isEmpty()) {
            return;
        }

        IncomingAttackContext context = contexts.pop();
        if (contexts.isEmpty()) {
            INCOMING_ATTACK_CONTEXTS.remove();
            // Normally consumed by defenseVerb() before attack() returns. Clear
            // any leftover marker for attack paths that suppress miss feedback.
            PARRY_FEEDBACK_TARGET.remove();
        }

        if (context.attacker != null && context.defender != null) {
            completeRiposte(context.defender, context.attacker);
        }
    }

    /** Source-build compatibility overload. Injected hooks use the stack-safe form. */
    public static void onIncomingAttackComplete(Char attacker, Char defender) {
        onIncomingAttackComplete();
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
                + ". When ON, incoming attacks trigger a counterattack using normal hit rules. "
                + "Force Hit is resolved before Parry and therefore overrides it. Tap to configure.";
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

    private static void prepareRiposte(Char riposter, Char attacker) {
        synchronized (PENDING_RIPOSTES) {
            RiposteActor pending = PENDING_RIPOSTES.get(attacker);
            if (pending != null && pending.scheduled) {
                return;
            }
            PENDING_RIPOSTES.put(attacker, new RiposteActor(riposter, attacker));
        }
    }

    private static void completeRiposte(Char riposter, Char attacker) {
        RiposteActor prepared = null;
        synchronized (PENDING_RIPOSTES) {
            RiposteActor pending = PENDING_RIPOSTES.get(attacker);
            if (pending == null
                    || pending.riposter != riposter
                    || pending.scheduled) {
                return;
            }
            pending.scheduled = true;
            prepared = pending;
        }

        if (prepared != null) {
            Actor.add(prepared);
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
            // attack(Char) is the stable SPD-family wrapper across old and new forks.
            boolean hit = riposter.attack(attacker);
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
        private boolean scheduled;

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
