package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoBuff;
import com.spd.mod.mechanics.ModLootBuff;
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

/** Redirects Loot's normal buff-icon click to its Scroll of Loot panel. */
public class ModLootBuffOverlay extends Gizmo {

    private static ModLootBuffOverlay instance;
    private static boolean installPending;

    private static Field groupMembersField;
    private static Field buffButtonsField;

    private final WeakHashMap<Component, LootButton> overlays = new WeakHashMap<>();

    public static void ensureInstalled() {
        if (!(ShatteredPixelDungeon.scene() instanceof GameScene) || !hasLootBuff()) {
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
                if (!(ShatteredPixelDungeon.scene() instanceof GameScene) || !hasLootBuff()) {
                    return;
                }

                Group scene = (Group) ShatteredPixelDungeon.scene();
                if (instance == null || !instance.exists || instance.parent != scene) {
                    instance = new ModLootBuffOverlay();
                    scene.addToFront(instance);
                }
            }
        });
    }

    @Override
    public void update() {
        super.update();

        if (!(ShatteredPixelDungeon.scene() instanceof GameScene)
                || parent != ShatteredPixelDungeon.scene()
                || !hasLootBuff()) {
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

    private static boolean hasLootBuff() {
        return Dungeon.hero != null && Dungeon.hero.buff(ModLootBuff.class) != null;
    }

    private void cleanupDeadOverlays() {
        Iterator<Map.Entry<Component, LootButton>> iterator = overlays.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Component, LootButton> entry = iterator.next();
            Component source = entry.getKey();
            LootButton overlay = entry.getValue();
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
                if (!(entry.getKey() instanceof ModLootBuff)
                        || !(entry.getValue() instanceof Component)) {
                    continue;
                }

                Component source = (Component) entry.getValue();
                if (!overlays.containsKey(source)) {
                    LootButton overlay = new LootButton((ModLootBuff) entry.getKey(), source);
                    indicator.addToFront(overlay);
                    overlays.put(source, overlay);
                }
            }
        } catch (Exception ignored) {
            // Optional UI extension failure must never break gameplay.
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
            // Reflection is isolated to this optional UI layer.
        }
    }

    private static class LootButton extends Button {

        private final ModLootBuff buff;
        private final Component source;

        LootButton(ModLootBuff buff, Component source) {
            this.buff = buff;
            this.source = source;
        }

        @Override
        public void update() {
            if (source.parent == null
                    || buff.target == null
                    || buff.target.buff(ModLootBuff.class) != buff) {
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
            buff.open();
        }

        @Override
        protected boolean onLongClick() {
            GameScene.show(new WndInfoBuff(buff));
            return true;
        }

        @Override
        protected void onRightClick() {
            GameScene.show(new WndInfoBuff(buff));
        }

        @Override
        protected String hoverText() {
            return buff.name();
        }
    }
}
