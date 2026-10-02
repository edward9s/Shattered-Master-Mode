package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.TargetHealthIndicator;
import com.spd.mod.journal.WndInstantKillInfo;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;

import java.util.ArrayDeque;


/** Permanent Char combat buff with a configurable Instant Kill effect. */
public class ModInstantKill extends Buff {

    private static final String INSTANT_KILL = "instant_kill";
    private static final ThreadLocal<ArrayDeque<AttackContext>> ATTACK_CONTEXTS =
            new ThreadLocal<>();
    private boolean instantKill = true;

    private static final class AttackContext {
        final Char attacker;
        final Char defender;

        AttackContext(Char attacker, Char defender) {
            this.attacker = attacker;
            this.defender = defender;
        }
    }

    private static ArrayDeque<AttackContext> attackContexts() {
        ArrayDeque<AttackContext> contexts = ATTACK_CONTEXTS.get();
        if (contexts == null) {
            contexts = new ArrayDeque<>();
            ATTACK_CONTEXTS.set(contexts);
        }
        return contexts;
    }

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
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

    public void toggleInstantKill() {
        instantKill = !instantKill;
        BuffIndicator.refreshHero();
    }

    public void openInfo() {
        if (target != null && find(target) == this) {
            GameScene.show(new WndInstantKillInfo(this));
        }
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
                "DUEL_CLEAVE",
                "PREPARATION",
                "MARK",
                "HEART");
    }

    @Override
    public void tintIcon(Image icon) {
        icon.hardlight(instantKill ? 0xFF4444 : 0xAAAAAA);
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
    public String toString() {
        // Legacy SPD-family Char.add() and WndInfoBuff use Buff.toString()
        // instead of name() for announcement text and the info-window title.
        return name();
    }

    @Override
    public String desc() {
        return "Successful attacks kill their target while enabled. "
                + "The native attack still resolves its hit presentation and sound first.";
    }


    /**
     * Captures the original attacker/defender at Char.attack entry. R8 may reuse
     * parameter registers later in the method, so return hooks must not read p0/p1.
     */
    public static void beginAttack(Char attacker, Char defender) {
        attackContexts().push(new AttackContext(attacker, defender));
    }

    /**
     * Completes one captured Char.attack invocation. Every normal return calls
     * this exactly once so nested attacks remain stack-safe.
     */
    public static void finishAttack(boolean successful) {
        ArrayDeque<AttackContext> contexts = ATTACK_CONTEXTS.get();
        if (contexts == null || contexts.isEmpty()) {
            return;
        }
        AttackContext context = contexts.pop();
        if (contexts.isEmpty()) {
            ATTACK_CONTEXTS.remove();
        }
        if (successful) {
            resolveSuccessfulAttack(context.attacker, context.defender);
        }
    }

    /**
     * Resolves Instant Kill for an attack that the native Char.attack path is
     * already returning as successful. Native attack presentation, including
     * weapon-specific hit sound, has already run before this hook resolves death.
     */
    public static boolean resolveSuccessfulAttack(Char attacker, Char defender) {
        ModInstantKill buff = find(attacker);
        if (buff == null
                || !buff.instantKill
                || buff.target != attacker
                || attacker == null
                || defender == null
                || defender == attacker
                || !attacker.isAlive()
                || !defender.isAlive()) {
            return false;
        }
        return executeInstantKill(attacker, defender);
    }

    private static boolean executeInstantKill(Char attacker, Char defender) {
        if (attacker == null
                || defender == null
                || defender == attacker
                || !defender.isAlive()) {
            return false;
        }

        // TargetHealthIndicator keeps a direct Char reference and calls isAlive()
        // every frame. Brute.isAlive() has side effects: after a forced first-stage
        // death, a later UI query can trigger BruteRage on the already-removed Char
        // and redraw its shield bar. Drop only this stale UI reference before the
        // native death path; normal target acquisition will replace it as usual.
        if (TargetHealthIndicator.instance != null
                && TargetHealthIndicator.instance.target() == defender) {
            TargetHealthIndicator.instance.target(null);
        }

        return ModDeathCompat.kill(defender, attacker);
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(INSTANT_KILL, instantKill);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        instantKill = bundle.getBoolean(INSTANT_KILL);
    }

}
