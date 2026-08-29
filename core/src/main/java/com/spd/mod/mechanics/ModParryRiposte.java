package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ChampionEnemy;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MonkEnergy;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.watabou.noosa.Image;
import com.watabou.utils.Bundle;

import java.util.HashSet;

/**
 * Permanent Master Mode combat buff with two mutually-exclusive modes.
 *
 * PARRY reuses SPD's generic FocusBuff hit hook, which makes every attack
 * roll against the target miss. RIPOSTE leaves incoming attacks untouched
 * and counters successful standard attacks with a guaranteed normal attack.
 *
 * The nested AttackWatcher is intentionally invisible and mechanically inert
 * except for ChampionEnemy's existing onAttackProc callback. This lets the
 * mod observe standard attacks without patching any vanilla Char/Mob/Hero
 * source files.
 */
public class ModParryRiposte extends Buff {

    public enum Mode {
        PARRY,
        RIPOSTE
    }

    private static final String MODE = "mode";
    private static final String OWNS_FOCUS = "owns_focus";

    private Mode mode = Mode.PARRY;
    private boolean ownsFocus;

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
    }

    public Mode mode() {
        return mode;
    }

    public static ModParryRiposte apply(Char target, Mode mode) {
        ModParryRiposte buff = target.buff(ModParryRiposte.class);
        if (buff == null) {
            buff = new ModParryRiposte();
            buff.mode = mode;
            if (!buff.attachTo(target)) {
                return null;
            }
            buff.maintainModeState();
        } else {
            buff.setMode(mode);
        }
        return buff;
    }

    public void setMode(Mode newMode) {
        if (newMode == null) {
            return;
        }

        Mode oldMode = mode;
        mode = newMode;
        maintainModeState();

        if (oldMode == Mode.RIPOSTE && newMode != Mode.RIPOSTE) {
            cleanupAttackWatchersIfUnused();
        }

        BuffIndicator.refreshHero();
    }

    private void maintainModeState() {
        if (target == null) {
            return;
        }

        if (mode == Mode.PARRY) {
            if (target.buff(MonkEnergy.MonkAbility.Focus.FocusBuff.class) == null) {
                Buff.affect(target, MonkEnergy.MonkAbility.Focus.FocusBuff.class);
                ownsFocus = true;
            }
        } else {
            // RIPOSTE is deliberately pure: do not keep the Focus parry effect.
            MonkEnergy.MonkAbility.Focus.FocusBuff focus =
                    target.buff(MonkEnergy.MonkAbility.Focus.FocusBuff.class);
            if (focus != null) {
                focus.detach();
            }
            ownsFocus = false;
            ensureAttackWatchers();
        }
    }

    @Override
    public boolean act() {
        maintainModeState();
        spend(TICK);
        return true;
    }

    @Override
    public void detach() {
        if (mode == Mode.PARRY && ownsFocus && target != null) {
            MonkEnergy.MonkAbility.Focus.FocusBuff focus =
                    target.buff(MonkEnergy.MonkAbility.Focus.FocusBuff.class);
            if (focus != null) {
                focus.detach();
            }
        }
        ownsFocus = false;

        super.detach();
        cleanupAttackWatchersIfUnused();
        BuffIndicator.refreshHero();
    }

    @Override
    public int icon() {
        return mode == Mode.PARRY ? BuffIndicator.DUEL_GUARD : BuffIndicator.DUEL_CLEAVE;
    }

    @Override
    public void tintIcon(Image icon) {
        if (mode == Mode.PARRY) {
            icon.hardlight(0x55CCFF);
        } else {
            icon.hardlight(0xFF5577);
        }
    }

    @Override
    public String iconTextDisplay() {
        return mode == Mode.PARRY ? "P" : "R";
    }

    @Override
    public String name() {
        return mode == Mode.PARRY ? "Mod Parry" : "Mod Riposte";
    }

    @Override
    public String desc() {
        if (mode == Mode.PARRY) {
            return "Permanent Master Mode buff. Parry mode makes every attack roll against this character miss. "
                    + "Use the Master Mode entry at the top of Journal > Buff to switch modes or remove it.";
        } else {
            return "Permanent Master Mode buff. Riposte mode leaves incoming attacks unchanged, but every successful "
                    + "standard attack triggers an immediate guaranteed normal counterattack whenever this character "
                    + "can attack the attacker. Use the Master Mode entry at the top of Journal > Buff to switch modes "
                    + "or remove it.";
        }
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(MODE, mode.name());
        bundle.put(OWNS_FOCUS, ownsFocus);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        String storedMode = bundle.getString(MODE);
        try {
            mode = Mode.valueOf(storedMode);
        } catch (Exception ignored) {
            mode = Mode.PARRY;
        }
        ownsFocus = bundle.getBoolean(OWNS_FOCUS);
    }

    private static boolean hasRiposteUser() {
        for (Char ch : Actor.chars()) {
            ModParryRiposte buff = ch.buff(ModParryRiposte.class);
            if (buff != null && buff.mode == Mode.RIPOSTE) {
                return true;
            }
        }
        return false;
    }

    private static void ensureAttackWatchers() {
        for (Char ch : Actor.chars()) {
            if (ch.buff(AttackWatcher.class) == null) {
                Buff.affect(ch, AttackWatcher.class);
            }
        }
    }

    private static void cleanupAttackWatchersIfUnused() {
        if (hasRiposteUser()) {
            return;
        }
        for (Char ch : Actor.chars()) {
            Buff.detach(ch, AttackWatcher.class);
        }
    }

    private static void scheduleRiposte(final Char riposter, final Char attacker) {
        Actor.add(new Actor() {
            {
                actPriority = VFX_PRIO;
            }

            @Override
            protected boolean act() {
                ModParryRiposte buff = riposter.buff(ModParryRiposte.class);
                if (buff != null
                        && buff.mode == Mode.RIPOSTE
                        && riposter.isAlive()
                        && attacker.isAlive()
                        && riposter.canAttack(attacker)) {
                    if (riposter.sprite != null) {
                        riposter.sprite.attack(attacker.pos);
                    }
                    riposter.attack(attacker, 1f, 0f, Char.INFINITE_ACCURACY);
                }

                Actor.remove(this);
                return true;
            }
        });
    }

    /**
     * Invisible observer attached while at least one RIPOSTE user exists.
     * All ChampionEnemy stat hooks remain at their vanilla neutral values.
     */
    public static class AttackWatcher extends ChampionEnemy {

        @Override
        public int icon() {
            return BuffIndicator.NONE;
        }

        @Override
        public void fx(boolean on) {
            // No champion aura: this is an implementation detail, not a status effect.
        }

        @Override
        public HashSet<Class> immunities() {
            // ChampionEnemy normally grants AllyBuff immunity; the watcher must be inert.
            return new HashSet<>();
        }

        @Override
        public HashSet<Class> resistances() {
            return new HashSet<>();
        }

        @Override
        public void onAttackProc(Char enemy) {
            if (target == null || enemy == null) {
                return;
            }

            ModParryRiposte defenderBuff = enemy.buff(ModParryRiposte.class);
            if (defenderBuff != null && defenderBuff.mode == Mode.RIPOSTE) {
                scheduleRiposte(enemy, target);
            }
        }

        @Override
        public boolean act() {
            if (!hasRiposteUser()) {
                detach();
                return true;
            }
            spend(TICK);
            return true;
        }
    }
}
