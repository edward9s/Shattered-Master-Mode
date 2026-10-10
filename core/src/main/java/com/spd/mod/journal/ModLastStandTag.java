package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Tag;
import com.spd.mod.mechanics.ModLastStand;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.TextureFilm;

import java.lang.reflect.Method;

/** Runtime edge Tag for opening Last Stand storage in full and ankh-only injection. */
public final class ModLastStandTag extends Gizmo {

    private static final int TAG_NEUTRAL = 0x7B8073;
    private static final int BADGE_RED = 0xFFC03838;
    private static final int HEART_YELLOW = 0xFFD54A;
    private static final float BADGE_SIZE = 11f;
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
        private final Image heart;
        private final ColorBlock[] badgeBorder = new ColorBlock[4];
        private final BitmapText count;
        private int lastCount = -1;

        LastStandTag() {
            super(TAG_NEUTRAL);
            setColor(TAG_NEUTRAL);

            icon = backpackIcon();
            add(icon);

            for (int i = 0; i < badgeBorder.length; i++) {
                badgeBorder[i] = new ColorBlock(1, 1, BADGE_RED);
                add(badgeBorder[i]);
            }

            heart = lastStandBadgeIcon();
            heart.hardlight(HEART_YELLOW);
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

        private static Image lastStandBadgeIcon() {
            // Keep source builds and injected builds on the exact same path.
            // Reflecting Assets.Interfaces.BUFFS_SMALL is unreliable in source
            // release builds because R8 may inline/remove that static final
            // field. The small buff-atlas resource path itself is stable across
            // the supported Pixel Dungeon lineage.
            Object atlas = "interfaces/buffs.png";
            Image image = new Image(atlas);
            TextureFilm film = new TextureFilm(atlas, 7, 7);
            image.frame(film.get(new ModLastStand().icon()));
            return image;
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
            heart.alpha(contentAlpha);
            count.alpha(contentAlpha);
            for (ColorBlock border : badgeBorder) {
                border.alpha(contentAlpha);
            }

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
