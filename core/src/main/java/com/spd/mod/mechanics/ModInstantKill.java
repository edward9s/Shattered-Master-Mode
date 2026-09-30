package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ChampionEnemy;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Preparation;
import com.shatteredpixel.shatteredpixeldungeon.effects.Wound;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.TargetHealthIndicator;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;

import java.lang.reflect.Method;
import java.util.HashSet;

/** Permanent Char combat buff with a configurable Instant Kill effect. */
public class ModInstantKill extends ChampionEnemy {

    private static final String INSTANT_KILL = "instant_kill";
    private static final String OPTIONAL_UI_PACKAGE = "com.spd.mod.journal.";
    private static final String OPTIONAL_UI_SIMPLE_NAME = "ModTotalInfoOverlay";

    private static boolean optionalUiResolved;
    private static Method ensureOptionalUiMethod;
    private static Method refreshOptionalUiMethod;

    private boolean instantKill = true;

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
        // Char.attackProc() already dispatches ChampionEnemy buffs for every Char.
        // Reuse that stable combat hook instead of adding another attack injection.
        // This buff otherwise has no champion side effects.
        color = 0xFFFFFF;
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
        refreshOptionalUi();
    }

    @Override
    public boolean attachTo(Char target) {
        if (!super.attachTo(target)) {
            return false;
        }
        ensureOptionalUi();
        return true;
    }

    @Override
    public void fx(boolean on) {
        // Do not inherit ChampionEnemy's aura or actor tint.
        if (on) {
            ensureOptionalUi();
        }
    }

    @Override
    public boolean act() {
        ensureOptionalUi();
        spend(TICK);
        return true;
    }

    @Override
    public void detach() {
        super.detach();
        BuffIndicator.refreshHero();
        refreshOptionalUi();
    }

    private static void ensureOptionalUi() {
        invokeOptionalUi(false);
    }

    private static void refreshOptionalUi() {
        invokeOptionalUi(true);
    }

    private static void invokeOptionalUi(boolean refresh) {
        resolveOptionalUi();
        Method method = refresh ? refreshOptionalUiMethod : ensureOptionalUiMethod;
        if (method == null) {
            return;
        }
        try {
            method.invoke(null);
        } catch (LinkageError e) {
            disableOptionalUi();
            return;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to invoke optional Instant Kill UI integration", e);
        }
    }

    private static void disableOptionalUi() {
        ensureOptionalUiMethod = null;
        refreshOptionalUiMethod = null;
        optionalUiResolved = true;
    }

    private static synchronized void resolveOptionalUi() {
        if (optionalUiResolved) {
            return;
        }

        String className = new StringBuilder(OPTIONAL_UI_PACKAGE)
                .append(OPTIONAL_UI_SIMPLE_NAME)
                .toString();
        try {
            Class<?> uiClass = Class.forName(
                    className,
                    false,
                    ModInstantKill.class.getClassLoader());
            ensureOptionalUiMethod = uiClass.getMethod("ensureInstalled");
            refreshOptionalUiMethod = uiClass.getMethod("refreshIndicators");
        } catch (ClassNotFoundException | LinkageError ignored) {
            // Expected in --ankh-only when the full UI class or one of its
            // transitive UI dependencies is unavailable in the target fork.
            disableOptionalUi();
            return;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Incompatible optional Instant Kill UI integration", e);
        }
        optionalUiResolved = true;
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
        return "Successful physical attacks kill their target while enabled.";
    }

    @Override
    public void onAttackProc(Char defender) {
        // Primary Instant Kill resolution happens immediately after Char.hit()
        // succeeds and before defenseProc(). Keep this as a compatibility
        // fallback for source/fork builds that have not installed that hook.
        resolveSuccessfulAttack(target, defender);
    }

    /**
     * Resolves Instant Kill after a physical hit has succeeded but before the
     * defender's defenseProc() runs. This prevents defensive side effects such
     * as Swarm splitting and works for synchronous, turn-free Assassinate.
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

    /**
     * Resolves an attack that was blocked by target invulnerability. Instant Kill
     * owns this policy; Force Hit never bypasses invulnerability by itself.
     */
    static boolean resolveBlockedAttack(Char attacker, Char defender) {
        ModInstantKill buff = find(attacker);
        if (buff == null
                || !buff.instantKill
                || buff.target != attacker
                || attacker == null
                || defender == null
                || defender == attacker
                || !attacker.isAlive()
                || !defender.isAlive()
                || !defender.isInvulnerable(attacker.getClass())) {
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

        Wound.hit(defender);

        // TargetHealthIndicator keeps a direct Char reference and calls isAlive()
        // every frame. Brute.isAlive() has side effects: after a forced first-stage
        // death, a later UI query can trigger BruteRage on the already-removed Char
        // and redraw its shield bar. Drop only this stale UI reference before the
        // native death path; normal target acquisition will replace it as usual.
        if (TargetHealthIndicator.instance != null
                && TargetHealthIndicator.instance.target() == defender) {
            TargetHealthIndicator.instance.target(null);
        }

        if (!ModDeathCompat.kill(defender, attacker)) {
            return false;
        }
        if (defender.sprite != null) {
            defender.sprite.showStatus(
                    CharSprite.NEGATIVE,
                    Messages.get(Preparation.class, "assassinated"));
        }
        return true;
    }

    @Override
    public float evasionAndAccuracyFactor() {
        return 1f;
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

    @Override
    public HashSet<Class> immunities() {
        return new HashSet<>();
    }

    @Override
    public HashSet<Class> resistances() {
        return new HashSet<>();
    }
}
