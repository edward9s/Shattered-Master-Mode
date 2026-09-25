package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.Tag;
import com.spd.mod.mechanics.ModLastStand;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.ColorBlock;
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

    private static final int TAG_NEUTRAL = 0x7B8073;
    private static final int BADGE_RED = 0xFFC03838;
    private static final int HEART_YELLOW = 0xFFD54A;
    private static final float BADGE_SIZE = 9f;

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
        private final Image heart;
        private final ColorBlock[] badgeBorder = new ColorBlock[4];
        private final BitmapText count;
        private int lastCount = -1;

        LastStandTag() {
            super(TAG_NEUTRAL);

            // Component creates Tag's chrome before Tag(int) has assigned its
            // RGB fields, so re-apply the intended neutral color here.
            setColor(TAG_NEUTRAL);

            // Prefer the target fork's semantic backpack icon. Forks such as
            // MLPD rearrange toolbar.png, so the vanilla (160, 0) frame is not
            // a stable binary-injection contract.
            icon = backpackIcon();
            add(icon);

            for (int i = 0; i < badgeBorder.length; i++) {
                badgeBorder[i] = new ColorBlock(1, 1, BADGE_RED);
                add(badgeBorder[i]);
            }

            heart = new BuffIcon(new ModLastStand(), true);
            heart.hardlight(HEART_YELLOW);
            heart.scale.set(PixelScene.align(0.42f));
            add(heart);

            count = new BitmapText(PixelScene.pixelFont);
            count.hardlight(0xFFFFFF);
            count.visible = false;
            add(count);

            setSize(SIZE, SIZE);
            refreshCount();
        }

        private static Image backpackIcon() {
            String uiPackage = Tag.class.getPackage().getName();

            try {
                Class<?> iconsClass = Class.forName(uiPackage + ".Icons");
                Method getMethod = iconsClass.getMethod("get");

                for (String name : new String[]{"BACKPACK_LRG", "BACKPACK"}) {
                    try {
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        Object iconType = Enum.valueOf(
                                (Class<? extends Enum>) iconsClass.asSubclass(Enum.class),
                                name);
                        Object value = getMethod.invoke(iconType);
                        if (value instanceof Image) {
                            return (Image) value;
                        }
                    } catch (IllegalArgumentException ignored) {
                        // Older forks may not define one of the semantic icons.
                    }
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Keep old SPD-family forks usable even if their Icons API differs.
            }

            Image fallback = new Image(Assets.Interfaces.TOOLBAR);
            fallback.frame(160, 0, 16, 16);
            return fallback;
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

            refreshCount();
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

            float badgeLeft = icon.x + icon.width() - BADGE_SIZE + 1f;
            float badgeTop = icon.y + icon.height() - BADGE_SIZE + 1f;

            badgeBorder[0].x = badgeLeft;
            badgeBorder[0].y = badgeTop;
            badgeBorder[0].size(BADGE_SIZE, 1f);

            badgeBorder[1].x = badgeLeft;
            badgeBorder[1].y = badgeTop + BADGE_SIZE - 1f;
            badgeBorder[1].size(BADGE_SIZE, 1f);

            badgeBorder[2].x = badgeLeft;
            badgeBorder[2].y = badgeTop;
            badgeBorder[2].size(1f, BADGE_SIZE);

            badgeBorder[3].x = badgeLeft + BADGE_SIZE - 1f;
            badgeBorder[3].y = badgeTop;
            badgeBorder[3].size(1f, BADGE_SIZE);

            heart.x = badgeLeft + (BADGE_SIZE - heart.width()) / 2f;
            heart.y = badgeTop + (BADGE_SIZE - heart.height()) / 2f;
            PixelScene.align(heart);

            if (count.visible) {
                count.x = icon.x - 1f;
                count.y = icon.y - 1f;
                PixelScene.align(count);
            }
        }

        private void refreshCount() {
            ModLastStand buff = ModLastStand.find(Dungeon.hero);
            int stored = buff == null ? 0 : buff.storage().size();
            if (stored == lastCount) {
                return;
            }

            lastCount = stored;
            count.visible = stored > 0;
            if (count.visible) {
                count.text(Integer.toString(stored));
                count.measure();
            }
            layout();
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
