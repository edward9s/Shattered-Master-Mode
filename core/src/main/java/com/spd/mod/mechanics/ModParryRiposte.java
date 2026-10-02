package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
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

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Permanent combat buff with independent Parry and Riposte controls. */
public class ModParryRiposte extends Buff {

    private static final String PARRY_ENABLED = "parry_enabled";
    private static final String RIPOSTE_ENABLED = "riposte_enabled";
    private static final String OWNS_PARRY_FOCUS = "owns_parry_focus";

    private static final ThreadLocal<ArrayDeque<IncomingAttackContext>> INCOMING_ATTACK_CONTEXTS =
            new ThreadLocal<>();
    private static final ThreadLocal<Map<Char, Boolean>> PARRY_FEEDBACK_TARGETS =
            new ThreadLocal<>();

    private static Class<? extends Buff> parryFocusClass;
    private static boolean parryFocusClassResolved;
    private static Field currentActorField;

    private static final ParryDetachSink PARRY_DETACH_SINK = new ParryDetachSink();
    private static final Map<Buff, ModParryRiposte> PARRY_FOCUS_OWNERS =
            Collections.synchronizedMap(new WeakHashMap<Buff, ModParryRiposte>());

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

    private static Map<Char, Boolean> parryFeedbackTargets() {
        Map<Char, Boolean> targets = PARRY_FEEDBACK_TARGETS.get();
        if (targets == null) {
            targets = new WeakHashMap<>();
            PARRY_FEEDBACK_TARGETS.set(targets);
        }
        return targets;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Buff> resolveParryFocusClass() {
        if (parryFocusClassResolved) {
            return parryFocusClass;
        }
        parryFocusClassResolved = true;

        String charName = Char.class.getName();
        String suffix = ".actors.Char";
        if (!charName.endsWith(suffix)) {
            return null;
        }

        String focusName = charName.substring(0, charName.length() - suffix.length())
                + ".actors.buffs.MonkEnergy$MonkAbility$Focus$FocusBuff";
        try {
            Class<?> raw = Class.forName(
                    focusName,
                    false,
                    ModParryRiposte.class.getClassLoader());
            if (Buff.class.isAssignableFrom(raw)) {
                parryFocusClass = (Class<? extends Buff>) raw;
            }
        } catch (ClassNotFoundException | LinkageError ignored) {
            // Older SPD forks can predate Monk Focus entirely.
        }
        return parryFocusClass;
    }

    private static Buff findExactParryFocus(Char ch) {
        Class<? extends Buff> focusClass = resolveParryFocusClass();
        if (ch == null || focusClass == null) {
            return null;
        }
        for (Buff focus : ch.buffs(focusClass)) {
            if (focus != null && focus.getClass() == focusClass) {
                return focus;
            }
        }
        return null;
    }

    private static boolean ownsParryFocus(Char ch, Buff focus) {
        ModParryRiposte owner = PARRY_FOCUS_OWNERS.get(focus);
        return owner != null && owner == find(ch);
    }

    /*
     * Char.attack entry/return hooks observe one normal attack. Keep one prepared
     * Riposte per attacker until the completion hook schedules it.
     */
    private static final Map<Char, RiposteActor> PENDING_RIPOSTES =
            Collections.synchronizedMap(new WeakHashMap<Char, RiposteActor>());

    private boolean parryEnabled = true;
    private boolean riposteEnabled = true;
    private boolean ownsParryFocus;
    private boolean restoringFromBundle;

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
        if (enabled) {
            ensureParryFocus();
        } else {
            detachAllExactParryFocus();
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
        if (defender != null) {
            // A fresh hit check invalidates only this defender's stale UI
            // marker, while preserving pending feedback for other targets in a
            // multi-target attack.
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
        if (buff != null
                && buff.riposteEnabled
                && !hasIncomingAttackContext(attacker, defender)) {
            queueDirectHitRiposte(defender, attacker);
        }

        boolean parried = buff != null
                && buff.target == defender
                && buff.parryEnabled;
        if (parried) {
            parryFeedbackTargets().put(defender, Boolean.TRUE);
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
        boolean hookedParry = false;
        Map<Char, Boolean> targets = PARRY_FEEDBACK_TARGETS.get();
        if (targets != null && targets.remove(defender) != null) {
            hookedParry = true;
            if (targets.isEmpty()) {
                PARRY_FEEDBACK_TARGETS.remove();
            }
        }

        Buff focus = findExactParryFocus(defender);
        boolean nativeFocusParry = focus != null && ownsParryFocus(defender, focus);

        if (!hookedParry && !nativeFocusParry) {
            return defender.defenseVerb();
        }

        if (!hookedParry && nativeFocusParry) {
            // Native Char.hit() used Focus to beat an otherwise guaranteed hit.
            // Detach through the sink so the exact Focus stays in the real
            // defender's buff set while also restoring direct-hit Riposte.
            focus.detach();
        }

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
        }

        // Normally consumed by defenseVerb() before attack() returns. Clear
        // only this defender if an attack path suppressed miss feedback.
        if (context.defender != null) {
            Map<Char, Boolean> targets = PARRY_FEEDBACK_TARGETS.get();
            if (targets != null) {
                targets.remove(context.defender);
                if (targets.isEmpty()) {
                    PARRY_FEEDBACK_TARGETS.remove();
                }
            }
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
    public boolean attachTo(Char target) {
        if (!super.attachTo(target)) {
            return false;
        }
        timeToNow();
        if (!restoringFromBundle && parryEnabled) {
            ensureParryFocus();
        }
        return true;
    }

    @Override
    public boolean act() {
        restoringFromBundle = false;
        if (parryEnabled) {
            ensureParryFocus();
        } else {
            detachAllExactParryFocus();
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
        if (!parryEnabled || target == null || !target.isAlive()) {
            return;
        }

        Class<? extends Buff> focusClass = resolveParryFocusClass();
        if (focusClass == null) {
            return;
        }

        Buff focus = findExactParryFocus(target);
        if (focus == null) {
            try {
                focus = focusClass.getDeclaredConstructor().newInstance();
            } catch (Exception | LinkageError ignored) {
                return;
            }
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

    private void detachAllExactParryFocus() {
        Class<? extends Buff> focusClass = resolveParryFocusClass();
        if (target == null || focusClass == null) {
            ownsParryFocus = false;
            return;
        }

        ArrayDeque<Buff> focuses = new ArrayDeque<>();
        for (Buff focus : target.buffs(focusClass)) {
            if (focus != null && focus.getClass() == focusClass) {
                focuses.add(focus);
            }
        }

        for (Buff focus : focuses) {
            PARRY_FOCUS_OWNERS.remove(focus);
            focus.target = target;
            focus.detach();
        }
        ownsParryFocus = false;
    }

    private void releaseParryFocus() {
        if (target == null) {
            return;
        }

        Buff focus = findExactParryFocus(target);
        if (focus == null || PARRY_FOCUS_OWNERS.get(focus) != this) {
            return;
        }

        PARRY_FOCUS_OWNERS.remove(focus);
        if (ownsParryFocus) {
            focus.target = target;
            focus.detach();
        } else {
            focus.target = target;
        }
        ownsParryFocus = false;
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
        bundle.put(OWNS_PARRY_FOCUS, ownsParryFocus);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        restoringFromBundle = true;
        super.restoreFromBundle(bundle);
        parryEnabled = !bundle.contains(PARRY_ENABLED) || bundle.getBoolean(PARRY_ENABLED);
        riposteEnabled = !bundle.contains(RIPOSTE_ENABLED) || bundle.getBoolean(RIPOSTE_ENABLED);
        ownsParryFocus = bundle.getBoolean(OWNS_PARRY_FOCUS);
    }

    private static boolean hasIncomingAttackContext(Char attacker, Char defender) {
        ArrayDeque<IncomingAttackContext> contexts = INCOMING_ATTACK_CONTEXTS.get();
        if (contexts == null) {
            return false;
        }
        for (IncomingAttackContext context : contexts) {
            if (context.attacker == attacker && context.defender == defender) {
                return true;
            }
        }
        return false;
    }

    private void onFocusParry() {
        queueRiposteFromCurrentAttack(currentAttackSource());
    }

    private void queueRiposteFromCurrentAttack(Char attacker) {
        if (riposteEnabled
                && target != null
                && target.isAlive()
                && attacker != null
                && attacker != target
                && attacker.isAlive()) {
            queueDirectHitRiposte(target, attacker);
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
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }

    private static void queueDirectHitRiposte(Char riposter, Char attacker) {
        synchronized (PENDING_RIPOSTES) {
            RiposteActor pending = PENDING_RIPOSTES.get(attacker);
            if (pending != null && pending.riposter == riposter) {
                return;
            }
            RiposteActor actor = new RiposteActor(riposter, attacker);
            actor.scheduled = true;
            PENDING_RIPOSTES.put(attacker, actor);
            Actor.add(actor);
        }
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
            boolean hit = ModCombatCompat.performRiposteAttack(riposter, attacker);
            if (hit) {
                ModCombatCompat.recordRiposteHit(riposter, attacker);
            }
        }
    }

    /**
     * FocusBuff.detach() removes itself from buff.target. Redirect that call to
     * this out-of-world sink so the exact native Focus remains in the real
     * defender's buff collection and can parry repeatedly.
     */
    private static class ParryDetachSink extends Hero {
        @Override
        public synchronized boolean remove(Buff buff) {
            ModParryRiposte owner = PARRY_FOCUS_OWNERS.get(buff);
            if (owner != null) {
                owner.onFocusParry();
                Actor.remove(buff);
                return true;
            }
            return super.remove(buff);
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
