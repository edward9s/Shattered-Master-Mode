package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;

import java.lang.reflect.Method;

/** Permanent Char buff with a configurable forced-hit effect. */
public class ModForceHit extends Buff {

    private static final String FORCE_HIT_ENABLED = "force_hit_enabled";
    private static final String OPTIONAL_UI_PACKAGE = "com.spd.mod.journal.";
    private static final String OPTIONAL_UI_SIMPLE_NAME = "ModTotalInfoOverlay";

    private static boolean optionalUiResolved;
    private static Method ensureOptionalUiMethod;
    private static Method refreshOptionalUiMethod;

    private boolean forceHitEnabled = true;

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
    }

    /** Returns the attached buff regardless of whether its effect is enabled. */
    public static ModForceHit findAttached(Char ch) {
        if (ch == null) {
            return null;
        }
        for (ModForceHit buff : ch.buffs(ModForceHit.class)) {
            return buff;
        }
        return null;
    }

    /** Returns the active effect; a disabled but attached buff returns null. */
    public static ModForceHit find(Char ch) {
        ModForceHit buff = findAttached(ch);
        return buff != null && buff.forceHitEnabled ? buff : null;
    }

    /**
     * Char.hit calls this before any native defense calculation. Returning true
     * resolves the hit immediately. Invulnerability remains an engine-level hard
     * stop and is never bypassed.
     */
    public static boolean forceHitCheck(Char attacker, Char defender) {
        return attacker != null
                && defender != null
                && attacker != defender
                && attacker.isAlive()
                && defender.isAlive()
                && find(attacker) != null
                && !defender.isInvulnerable(attacker.getClass());
    }

    public boolean forceHitEnabled() {
        return forceHitEnabled;
    }

    public void toggleForceHit() {
        forceHitEnabled = !forceHitEnabled;
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
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to invoke optional Force Hit UI integration", e);
        }
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
                    ModForceHit.class.getClassLoader());
            ensureOptionalUiMethod = uiClass.getMethod("ensureInstalled");
            refreshOptionalUiMethod = uiClass.getMethod("refreshIndicators");
        } catch (ClassNotFoundException ignored) {
            // Expected in --ankh-only: Force Hit works without full-SMM UI.
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Incompatible optional Force Hit UI integration", e);
        }
        optionalUiResolved = true;
    }

    @Override
    public int icon() {
        return ModBuffIconCompat.getFirst(
                "INVERT_MARK",
                "MARK",
                "HEART");
    }

    @Override
    public void tintIcon(Image icon) {
        icon.hardlight(forceHitEnabled ? 0x55CCFF : 0xAAAAAA);
    }

    @Override
    public String iconTextDisplay() {
        return "H";
    }

    @Override
    public String name() {
        return "Force Hit";
    }

    @Override
    public String toString() {
        return name();
    }

    @Override
    public String desc() {
        return "Forces this character's hit checks to succeed whenever the target can be hit. "
                + "Invulnerability is not bypassed.";
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(FORCE_HIT_ENABLED, forceHitEnabled);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        // Saves from before the checkbox existed preserve the old always-ON behavior.
        forceHitEnabled = !bundle.contains(FORCE_HIT_ENABLED)
                || bundle.getBoolean(FORCE_HIT_ENABLED);
    }
}
