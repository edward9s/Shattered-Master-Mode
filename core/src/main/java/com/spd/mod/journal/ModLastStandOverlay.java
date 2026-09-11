package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.spd.mod.mechanics.ModLastStand;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.ui.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/** Redirects Last Stand's normal buff-icon click to its Loot-storage panel. */
public class ModLastStandOverlay extends Gizmo {

    private static ModLastStandOverlay instance;
    private static Field groupMembersField;

    private final WeakHashMap<Component, LastStandButton> overlays = new WeakHashMap<>();

    public static void ensureInstalled() {
        if (!(ShatteredPixelDungeon.scene() instanceof GameScene) || !hasLastStandBuff()) {
            return;
        }

        Group scene = (Group) ShatteredPixelDungeon.scene();
        if (instance != null && instance.exists && instance.parent == scene) {
            return;
        }

        // Use only long-lived Noosa Group APIs here. Some legacy SPD forks used
        // by --ankh-only do not expose newer render-thread helpers.
        instance = new ModLastStandOverlay();
        scene.add(instance);
    }

    @Override
    public void update() {
        super.update();

        if (!(ShatteredPixelDungeon.scene() instanceof GameScene)
                || parent != ShatteredPixelDungeon.scene()
                || !hasLastStandBuff()) {
            killAndErase();
            if (instance == this) {
                instance = null;
            }
            return;
        }

        cleanupDeadOverlays();

        ArrayList<Component> components = new ArrayList<>();
        collectComponents((Group) ShatteredPixelDungeon.scene(), components);
        for (Component source : components) {
            if (source instanceof LastStandButton || overlays.containsKey(source)) {
                continue;
            }

            ModLastStand buff = lastStandBuff(source);
            if (buff == null || source.parent == null) {
                continue;
            }

            LastStandButton overlay = new LastStandButton(buff, source);
            source.parent.add(overlay);
            overlays.put(source, overlay);
        }
    }

    private static boolean hasLastStandBuff() {
        return ModLastStand.find(Dungeon.hero) != null;
    }

    private void cleanupDeadOverlays() {
        Iterator<Map.Entry<Component, LastStandButton>> iterator = overlays.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Component, LastStandButton> entry = iterator.next();
            Component source = entry.getKey();
            LastStandButton overlay = entry.getValue();
            if (source == null || source.parent == null || overlay == null || overlay.parent == null) {
                if (overlay != null && overlay.parent != null) {
                    overlay.killAndErase();
                }
                iterator.remove();
            }
        }
    }

    /**
     * Locate a component which owns the Last Stand buff without depending on a
     * particular BuffIndicator private-field layout. Old forks and release builds
     * use different field names, but their button still stores the Buff instance.
     */
    private static ModLastStand lastStandBuff(Component component) {
        for (Class<?> cls = component.getClass(); cls != null; cls = cls.getSuperclass()) {
            Field[] fields;
            try {
                fields = cls.getDeclaredFields();
            } catch (RuntimeException ignored) {
                continue;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())
                        || !Buff.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(component);
                    if (value instanceof ModLastStand) {
                        return (ModLastStand) value;
                    }
                } catch (ReflectiveOperationException | SecurityException ignored) {
                    // Keep looking through the component hierarchy.
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void collectComponents(Group group, ArrayList<Component> result) {
        try {
            if (groupMembersField == null) {
                groupMembersField = Group.class.getDeclaredField("members");
                groupMembersField.setAccessible(true);
            }

            ArrayList<Gizmo> members = new ArrayList<>((ArrayList<Gizmo>) groupMembersField.get(group));
            for (Gizmo child : members) {
                if (child instanceof Component) {
                    result.add((Component) child);
                }
                if (child instanceof Group) {
                    collectComponents((Group) child, result);
                }
            }
        } catch (Exception ignored) {
            // Optional presentation failure must never affect Last Stand itself.
        }
    }

    private static class LastStandButton extends Button {

        private final ModLastStand buff;
        private final Component source;

        LastStandButton(ModLastStand buff, Component source) {
            this.buff = buff;
            this.source = source;
        }

        @Override
        public void update() {
            if (source.parent == null || !buff.isAttached()) {
                killAndErase();
                return;
            }

            setRect(source.left(), source.top(), source.width(), source.height());
            visible = source.visible;
            active = source.active;
            super.update();
        }

        @Override
        protected void onClick() {
            buff.open();
        }

        @Override
        protected boolean onLongClick() {
            buff.open();
            return true;
        }

        @Override
        protected void onRightClick() {
            buff.open();
        }

        @Override
        protected String hoverText() {
            return buff.name();
        }
    }
}
