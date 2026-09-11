package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.spd.mod.mechanics.ModAssassinate;
import com.spd.mod.mechanics.ModForceHit;
import com.spd.mod.mechanics.ModInstantKill;
import com.spd.mod.mechanics.ModParryRiposte;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.Callback;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Installs transparent click layers over configurable Master Mode buff icons.
 * SPD's BuffIndicator hardcodes WndInfoBuff, so this mod-side overlay is the
 * only way to open extended information windows without modifying vanilla source.
 */
public class ModTotalInfoOverlay extends Gizmo {

    private static ModTotalInfoOverlay instance;
    private static boolean installPending;

    private static Field groupMembersField;
    private static Field buffButtonsField;
    private static Field needsRefreshField;

    private final WeakHashMap<Component, ConfigInfoButton> overlays = new WeakHashMap<>();

    public static void ensureInstalled() {
        if (!(ShatteredPixelDungeon.scene() instanceof GameScene)) {
            return;
        }

        if (instance != null
                && instance.exists
                && instance.parent == ShatteredPixelDungeon.scene()) {
            return;
        }

        if (installPending) {
            return;
        }
        installPending = true;

        ShatteredPixelDungeon.runOnRenderThread(new Callback() {
            @Override
            public void call() {
                installPending = false;
                if (!(ShatteredPixelDungeon.scene() instanceof GameScene)) {
                    return;
                }

                Group scene = (Group) ShatteredPixelDungeon.scene();
                if (instance == null || !instance.exists || instance.parent != scene) {
                    instance = new ModTotalInfoOverlay();
                    scene.addToFront(instance);
                }
            }
        });
    }

    /** Refreshes every currently visible BuffIndicator after a mod buff state changes. */
    public static void refreshIndicators() {
        ShatteredPixelDungeon.runOnRenderThread(new Callback() {
            @Override
            public void call() {
                if (!(ShatteredPixelDungeon.scene() instanceof GameScene)) {
                    return;
                }

                try {
                    if (needsRefreshField == null) {
                        needsRefreshField = BuffIndicator.class.getDeclaredField("needsRefresh");
                        needsRefreshField.setAccessible(true);
                    }

                    ArrayList<BuffIndicator> indicators = new ArrayList<>();
                    collectBuffIndicators((Group) ShatteredPixelDungeon.scene(), indicators);
                    for (BuffIndicator indicator : indicators) {
                        needsRefreshField.setBoolean(indicator, true);
                    }
                } catch (Exception ignored) {
                    // The hero indicator still has its normal refresh path.
                }
            }
        });
    }

    @Override
    public void update() {
        super.update();

        if (!(ShatteredPixelDungeon.scene() instanceof GameScene)
                || parent != ShatteredPixelDungeon.scene()
                || !hasConfigurableUser()) {
            killAndErase();
            if (instance == this) {
                instance = null;
            }
            return;
        }

        cleanupDeadOverlays();

        ArrayList<BuffIndicator> indicators = new ArrayList<>();
        collectBuffIndicators((Group) ShatteredPixelDungeon.scene(), indicators);
        for (BuffIndicator indicator : indicators) {
            installForIndicator(indicator);
        }
    }

    private static boolean hasConfigurableUser() {
        for (Char ch : Actor.chars()) {
            if (ModParryRiposte.find(ch) != null
                    || ModInstantKill.find(ch) != null
                    || ModForceHit.findAttached(ch) != null
                    || ModAssassinate.find(ch) != null) {
                return true;
            }
        }
        return false;
    }

    private void cleanupDeadOverlays() {
        Iterator<Map.Entry<Component, ConfigInfoButton>> iterator = overlays.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Component, ConfigInfoButton> entry = iterator.next();
            Component source = entry.getKey();
            ConfigInfoButton overlay = entry.getValue();
            if (source == null || source.parent == null || overlay == null || overlay.parent == null) {
                if (overlay != null && overlay.parent != null) {
                    overlay.killAndErase();
                }
                iterator.remove();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void installForIndicator(BuffIndicator indicator) {
        try {
            if (buffButtonsField == null) {
                buffButtonsField = BuffIndicator.class.getDeclaredField("buffButtons");
                buffButtonsField.setAccessible(true);
            }

            LinkedHashMap<Object, Object> buffButtons =
                    (LinkedHashMap<Object, Object>) buffButtonsField.get(indicator);

            for (Map.Entry<Object, Object> entry : buffButtons.entrySet()) {
                if (!(entry.getKey() instanceof ModParryRiposte)
                        && !(entry.getKey() instanceof ModInstantKill)
                        && !(entry.getKey() instanceof ModForceHit)
                        && !(entry.getKey() instanceof ModAssassinate)) {
                    continue;
                }
                if (!(entry.getValue() instanceof Component)) {
                    continue;
                }

                Component source = (Component) entry.getValue();
                if (!overlays.containsKey(source)) {
                    ConfigInfoButton overlay = new ConfigInfoButton((Buff) entry.getKey(), source);
                    indicator.addToFront(overlay);
                    overlays.put(source, overlay);
                }
            }
        } catch (Exception ignored) {
            // UI extension failure must never break gameplay.
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectBuffIndicators(Group group, ArrayList<BuffIndicator> result) {
        if (group instanceof BuffIndicator) {
            result.add((BuffIndicator) group);
        }

        try {
            if (groupMembersField == null) {
                groupMembersField = Group.class.getDeclaredField("members");
                groupMembersField.setAccessible(true);
            }

            ArrayList<Gizmo> members = new ArrayList<>((ArrayList<Gizmo>) groupMembersField.get(group));
            for (Gizmo child : members) {
                if (child instanceof Group) {
                    collectBuffIndicators((Group) child, result);
                }
            }
        } catch (Exception ignored) {
            // Reflection is intentionally isolated to this optional UI layer.
        }
    }

    private static class ConfigInfoButton extends Button {

        private final Buff buff;
        private final Component source;

        ConfigInfoButton(Buff buff, Component source) {
            this.buff = buff;
            this.source = source;
        }

        @Override
        public void update() {
            if (source.parent == null || !isCurrentBuff()) {
                killAndErase();
                return;
            }

            setRect(source.left(), source.top(), source.width(), source.height());
            visible = source.visible;
            active = source.active;

            super.update();
            givePointerPriority();
        }

        private boolean isCurrentBuff() {
            if (buff.target == null) {
                return false;
            }
            if (buff instanceof ModParryRiposte) {
                return ModParryRiposte.find(buff.target) == buff;
            }
            if (buff instanceof ModInstantKill) {
                return ModInstantKill.find(buff.target) == buff;
            }
            if (buff instanceof ModForceHit) {
                return ModForceHit.findAttached(buff.target) == buff;
            }
            if (buff instanceof ModAssassinate) {
                return ModAssassinate.find(buff.target) == buff;
            }
            return false;
        }

        @Override
        protected void onClick() {
            if (buff instanceof ModParryRiposte) {
                GameScene.show(new WndTotalBuffInfo((ModParryRiposte) buff));
            } else if (buff instanceof ModInstantKill) {
                GameScene.show(new WndInstantKillInfo((ModInstantKill) buff));
            } else if (buff instanceof ModForceHit) {
                GameScene.show(new WndForceHitInfo((ModForceHit) buff));
            } else if (buff instanceof ModAssassinate) {
                GameScene.show(new WndAssassinBuffInfo((ModAssassinate) buff));
            }
        }

        @Override
        protected String hoverText() {
            return buff.name();
        }
    }
}
