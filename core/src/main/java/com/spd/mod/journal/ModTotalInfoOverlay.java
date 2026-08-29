package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
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
 * Installs a transparent click layer over Total's normal BuffIndicator icon.
 * SPD's BuffIndicator hardcodes WndInfoBuff, so this mod-side overlay is the
 * only way to open Total's extended information window without modifying any
 * vanilla source file.
 *
 * The overlay is visual-free: the original SPD buff icon is still rendered and
 * laid out normally. Only its click action is replaced for Total.
 */
public class ModTotalInfoOverlay extends Gizmo {

    private static ModTotalInfoOverlay instance;
    private static boolean installPending;

    private static Field groupMembersField;
    private static Field buffButtonsField;
    private static Field needsRefreshField;

    private final WeakHashMap<Component, TotalInfoButton> overlays = new WeakHashMap<>();

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

    /** Refreshes every currently visible BuffIndicator after the Total state changes. */
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
                || !hasTotalUser()) {
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

    private static boolean hasTotalUser() {
        for (Char ch : Actor.chars()) {
            if (ch.buff(ModParryRiposte.class) != null) {
                return true;
            }
        }
        return false;
    }

    private void cleanupDeadOverlays() {
        Iterator<Map.Entry<Component, TotalInfoButton>> iterator = overlays.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Component, TotalInfoButton> entry = iterator.next();
            Component source = entry.getKey();
            TotalInfoButton overlay = entry.getValue();
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
                        || !(entry.getValue() instanceof Component)) {
                    continue;
                }

                Component source = (Component) entry.getValue();
                if (!overlays.containsKey(source)) {
                    TotalInfoButton overlay = new TotalInfoButton(
                            (ModParryRiposte) entry.getKey(), source);
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

    private static class TotalInfoButton extends Button {

        private final ModParryRiposte buff;
        private final Component source;

        TotalInfoButton(ModParryRiposte buff, Component source) {
            this.buff = buff;
            this.source = source;
        }

        @Override
        public void update() {
            if (source.parent == null
                    || buff.target == null
                    || buff.target.buff(ModParryRiposte.class) != buff) {
                killAndErase();
                return;
            }

            setRect(source.left(), source.top(), source.width(), source.height());
            visible = source.visible;
            active = source.active;

            super.update();
            givePointerPriority();
        }

        @Override
        protected void onClick() {
            GameScene.show(new WndTotalBuffInfo(buff));
        }

        @Override
        protected String hoverText() {
            return buff.name();
        }
    }
}
