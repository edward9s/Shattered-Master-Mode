package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Tag;
import com.spd.mod.mechanics.ModLastStand;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;

import java.lang.reflect.Method;

/** Runtime edge Tag for opening Last Stand storage in full and ankh-only injection. */
public final class ModLastStandTag extends Gizmo {

    private static final int TAG_NEUTRAL = 0x7B8073;
    private static ModLastStandTag instance;
    private LastStandTag storeTag;

    public static void ensureInstalled() {
        if (!(Game.scene() instanceof GameScene) || !hasLastStandBuff()) {
            return;
        }

        Group scene = (Group) Game.scene();
        if (instance != null && instance.exists && instance.parent == scene) {
            return;
        }

        instance = new ModLastStandTag();
        scene.add(instance);
        instance.ensureStoreTag(scene);
    }

    @Override
    public void update() {
        super.update();

        if (!(Game.scene() instanceof GameScene)
                || parent != Game.scene()
                || !hasLastStandBuff()) {
            removeStoreTag();
            killAndErase();
            if (instance == this) {
                instance = null;
            }
            return;
        }

        Group scene = (Group) Game.scene();
        ensureStoreTag(scene);
        ModRuntimeTagStack.layout();
    }

    private void ensureStoreTag(Group scene) {
        if (storeTag != null && storeTag.exists && storeTag.parent == scene) {
            if (!ModRuntimeTagStack.hasOpenWindow()) {
                scene.bringToFront(storeTag);
            }
            return;
        }

        if (ModRuntimeTagStack.hasOpenWindow()) {
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

    @Override
    public void destroy() {
        removeStoreTag();
        if (instance == this) {
            instance = null;
        }
        super.destroy();
    }

    private static final class LastStandTag extends Tag {

        private final Image icon;
        private final BitmapText count;
        private int lastCount = -1;

        LastStandTag() {
            super(TAG_NEUTRAL);
            setColor(TAG_NEUTRAL);

            icon = backpackIcon();
            add(icon);

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
                        // Older full-SMM targets may not define one semantic icon.
                    }
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Fall back to the long-standing toolbar frame.
            }

            Image fallback = new Image(Assets.Interfaces.TOOLBAR);
            fallback.frame(160, 0, 16, 16);
            return fallback;
        }

        @Override
        public void update() {
            if (!(Game.scene() instanceof GameScene)
                    || parent != Game.scene()
                    || !hasLastStandBuff()) {
                ModRuntimeTagStack.unregister(this);
                killAndErase();
                return;
            }

            refreshCount();
            ModRuntimeTagStack.layout();

            boolean available = Dungeon.hero != null && Dungeon.hero.ready;
            float contentAlpha = available ? 1f : 0.4f;
            icon.alpha(contentAlpha);
            count.alpha(contentAlpha);

            super.update();
            if (!ModRuntimeTagStack.hasOpenWindow()) {
                ModRuntimeTagStack.givePointerPriority(this);
            }
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
            if (Dungeon.hero == null || !Dungeon.hero.ready) {
                return;
            }

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
}
