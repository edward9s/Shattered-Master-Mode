package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.Tag;
import com.spd.mod.mechanics.ModLastStand;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.ui.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/** Redirects Last Stand's buff-icon click and exposes its Store as an edge Tag. */
public class ModLastStandOverlay extends Gizmo {

    private static ModLastStandOverlay instance;
    private static Field groupMembersField;
    private static Method givePointerPriorityMethod;
    private static boolean pointerPriorityResolved;

    private final WeakHashMap<Component, LastStandButton> overlays = new WeakHashMap<>();
    private LastStandTag storeTag;

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
        instance.ensureStoreTag(scene);
    }

    @Override
    public void update() {
        super.update();

        if (!(ShatteredPixelDungeon.scene() instanceof GameScene)
                || parent != ShatteredPixelDungeon.scene()
                || !hasLastStandBuff()) {
            removeStoreTag();
            killAndErase();
            if (instance == this) {
                instance = null;
            }
            return;
        }

        Group scene = (Group) ShatteredPixelDungeon.scene();
        ensureStoreTag(scene);
        ModRuntimeTagStack.layout();

        cleanupDeadOverlays();

        ArrayList<Component> components = new ArrayList<>();
        collectComponents(scene, components);
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

    private void ensureStoreTag(Group scene) {
        if (storeTag != null && storeTag.exists && storeTag.parent == scene) {
            return;
        }

        if (storeTag != null) {
            ModRuntimeTagStack.unregister(storeTag);
        }

        storeTag = new LastStandTag();
        storeTag.camera = PixelScene.uiCamera;
        scene.addToFront(storeTag);
        ModRuntimeTagStack.register(storeTag, 10);
    }

    private void removeStoreTag() {
        LastStandTag tag = storeTag;
        storeTag = null;
        if (tag == null) {
            return;
        }

        ModRuntimeTagStack.unregister(tag);
        if (tag.exists) {
            tag.killAndErase();
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

    private static void givePointerPriorityCompat(Component component) {
        if (!pointerPriorityResolved) {
            pointerPriorityResolved = true;
            for (Class<?> cls = component.getClass(); cls != null; cls = cls.getSuperclass()) {
                try {
                    Method method = cls.getDeclaredMethod("givePointerPriority");
                    method.setAccessible(true);
                    givePointerPriorityMethod = method;
                    break;
                } catch (NoSuchMethodException ignored) {
                    // Continue through the legacy component hierarchy.
                } catch (SecurityException ignored) {
                    break;
                }
            }
        }

        if (givePointerPriorityMethod != null) {
            try {
                givePointerPriorityMethod.invoke(component);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Adding the overlay after the source still gives a useful fallback.
            }
        }
    }

    /**
     * Reuse the target fork's own buff-button click implementation to show info.
     * This avoids depending on any particular WndInfoBuff constructor or layout.
     */
    private static boolean openNativeBuffInfo(Component source) {
        for (Class<?> cls = source.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                Method method = cls.getDeclaredMethod("onClick");
                method.setAccessible(true);
                method.invoke(source);
                return true;
            } catch (NoSuchMethodException ignored) {
                // Continue through the legacy component hierarchy.
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        removeStoreTag();
        if (instance == this) {
            instance = null;
        }
        super.destroy();
    }

    private static class LastStandTag extends Tag {

        private final Image icon;

        LastStandTag() {
            super(0x444444);

            icon = new BuffIcon(new ModLastStand(), true);
            add(icon);

            setSize(SIZE, SIZE);
        }

        @Override
        public void update() {
            if (!(ShatteredPixelDungeon.scene() instanceof GameScene)
                    || parent != ShatteredPixelDungeon.scene()
                    || !hasLastStandBuff()) {
                ModRuntimeTagStack.unregister(this);
                killAndErase();
                return;
            }

            ModRuntimeTagStack.layout();
            super.update();
            givePointerPriorityCompat(this);
        }

        @Override
        protected void layout() {
            super.layout();

            if (!flipped) {
                icon.x = x + (SIZE - icon.width()) / 2f + 1f;
            } else {
                icon.x = x + width - (SIZE + icon.width()) / 2f - 1f;
            }
            icon.y = y + (height - icon.height()) / 2f;
            PixelScene.align(icon);
        }

        @Override
        protected void onClick() {
            super.onClick();
            ModLastStand buff = ModLastStand.find(Dungeon.hero);
            if (buff != null) {
                buff.open();
            }
        }

        @Override
        protected String hoverText() {
            return "Last Stand Store";
        }

        @Override
        public void destroy() {
            ModRuntimeTagStack.unregister(this);
            super.destroy();
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
            givePointerPriorityCompat(this);
        }

        @Override
        protected void onClick() {
            buff.open();
        }

        @Override
        protected boolean onLongClick() {
            if (!openNativeBuffInfo(source)) {
                buff.open();
            }
            return true;
        }

        @Override
        protected void onRightClick() {
            if (!openNativeBuffInfo(source)) {
                buff.open();
            }
        }

        @Override
        protected String hoverText() {
            return buff.name();
        }
    }
}
