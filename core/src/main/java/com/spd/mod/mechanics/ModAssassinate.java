package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.QuickSlotButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Tag;
import com.spd.mod.journal.ModTotalInfoOverlay;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.PointerArea;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;
import com.watabou.utils.PointF;
import com.watabou.utils.Signal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Permanent Hero buff that exposes Assassinate through an edge Tag and an
 * optional map long-press gesture.
 */
public class ModAssassinate extends Buff {

    private static final String ASSASSIN_ENABLED = "assassin_enabled";
    private static final String MAP_LONG_PRESS_ENABLED = "map_long_press_enabled";

    private static LongPressLayer inputLayer;
    private static boolean installPending;

    private static Field cellSelectorField;
    private static Field defaultCellListenerField;
    private static Field selectorEventField;

    private boolean assassinEnabled = true;
    private boolean mapLongPressEnabled;

    {
        type = buffType.POSITIVE;
        announced = true;
        revivePersists = true;
    }

    /** Stable across supported SPD forks; avoids Char.buff(Class) ABI variance. */
    public static ModAssassinate find(Char ch) {
        if (ch == null) {
            return null;
        }
        for (ModAssassinate buff : ch.buffs(ModAssassinate.class)) {
            return buff;
        }
        return null;
    }

    public boolean assassinEnabled() {
        return assassinEnabled;
    }

    public boolean mapLongPressEnabled() {
        return mapLongPressEnabled;
    }

    public void toggleAssassin() {
        assassinEnabled = !assassinEnabled;
        if (assassinEnabled) {
            refreshRuntimeUi();
        } else {
            AssassinateTag.disable();
            removeInputLayer();
        }
        BuffIndicator.refreshHero();
        ModTotalInfoOverlay.refreshIndicators();
    }

    public void toggleMapLongPress() {
        mapLongPressEnabled = !mapLongPressEnabled;
        if (assassinEnabled && mapLongPressEnabled) {
            ensureInputLayer();
        } else {
            removeInputLayer();
        }
    }

    private void refreshRuntimeUi() {
        ModTotalInfoOverlay.ensureInstalled();
        if (!assassinEnabled) {
            return;
        }

        AssassinateTag.ensureInstalled();
        if (mapLongPressEnabled) {
            ensureInputLayer();
        }
    }

    @Override
    public boolean attachTo(Char target) {
        if (!(target instanceof Hero)) {
            return false;
        }
        if (!super.attachTo(target)) {
            return false;
        }
        refreshRuntimeUi();
        return true;
    }

    @Override
    public void fx(boolean on) {
        if (on) {
            refreshRuntimeUi();
        }
    }

    @Override
    public boolean act() {
        refreshRuntimeUi();
        spend(TICK);
        return true;
    }

    @Override
    public void detach() {
        super.detach();
        AssassinateTag.disable();
        removeInputLayer();
        BuffIndicator.refreshHero();
    }

    @Override
    public int icon() {
        return ModBuffIconCompat.get("PREPARATION");
    }

    @Override
    public void tintIcon(Image icon) {
        icon.hardlight(assassinEnabled ? 0xB06CFF : 0xAAAAAA);
    }

    @Override
    public String iconTextDisplay() {
        return "A";
    }

    @Override
    public String name() {
        return "Assassinate";
    }

    @Override
    public String desc() {
        return "While enabled, the edge target button enters Assassinate targeting. "
                + "Press it again to use the current crosshair target, or tap a map cell or character directly. "
                + "Map long-press is optional and disabled by default. "
                + "Attacks follow SPD's normal surprise-attack and weapon accuracy rules. Tap to configure.";
    }

    private static boolean assassinEnabledForHero() {
        if (!(ShatteredPixelDungeon.scene() instanceof GameScene) || Dungeon.hero == null) {
            return false;
        }
        ModAssassinate buff = find(Dungeon.hero);
        return buff != null && buff.assassinEnabled;
    }

    private static boolean mapLongPressEnabledForHero() {
        if (!assassinEnabledForHero()) {
            return false;
        }
        ModAssassinate buff = find(Dungeon.hero);
        return buff != null && buff.mapLongPressEnabled;
    }

    private static void ensureInputLayer() {
        if (!mapLongPressEnabledForHero()) {
            return;
        }

        if (inputLayer != null
                && inputLayer.exists
                && inputLayer.parent == ShatteredPixelDungeon.scene()) {
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
                if (!mapLongPressEnabledForHero()) {
                    return;
                }

                CellSelector selector = currentCellSelector();
                if (selector == null) {
                    return;
                }

                Group scene = (Group) ShatteredPixelDungeon.scene();
                if (inputLayer == null || !inputLayer.exists || inputLayer.parent != scene) {
                    inputLayer = new LongPressLayer(selector);
                    scene.addToFront(inputLayer);
                }
            }
        });
    }

    private static void removeInputLayer() {
        final LongPressLayer layer = inputLayer;
        if (layer == null) {
            return;
        }

        ShatteredPixelDungeon.runOnRenderThread(new Callback() {
            @Override
            public void call() {
                if (inputLayer == layer) {
                    inputLayer = null;
                }
                if (layer.exists) {
                    layer.killAndErase();
                }
            }
        });
    }

    private static CellSelector currentCellSelector() {
        try {
            if (cellSelectorField == null) {
                cellSelectorField = GameScene.class.getDeclaredField("cellSelector");
                cellSelectorField.setAccessible(true);
            }
            return (CellSelector) cellSelectorField.get(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object defaultCellListener() {
        try {
            if (defaultCellListenerField == null) {
                defaultCellListenerField = GameScene.class.getDeclaredField("defaultCellListener");
                defaultCellListenerField.setAccessible(true);
            }
            return defaultCellListenerField.get(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static PointerEvent selectorEvent(CellSelector selector) {
        try {
            if (selectorEventField == null) {
                selectorEventField = PointerArea.class.getDeclaredField("curEvent");
                selectorEventField.setAccessible(true);
            }
            return (PointerEvent) selectorEventField.get(selector);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean normalMapMode(CellSelector selector) {
        Object defaultListener = defaultCellListener();
        return selector != null
                && selector.enabled
                && defaultListener != null
                && selector.listener == defaultListener;
    }

    private static boolean isCancelEvent(PointerEvent event) {
        // CANCEL does not exist in some older SPD-family PointerEvent.Type enums.
        // Compare its runtime enum text instead of linking the payload against
        // PointerEvent.Type.CANCEL as a mandatory target field.
        return event != null && "CANCEL".equals(String.valueOf(event.type));
    }

    private static class LongPressLayer extends Gizmo implements Signal.Listener<PointerEvent> {

        private final CellSelector selector;
        private final float dragThreshold;

        private PointerEvent press;
        private float heldTime;
        private boolean cancelled;

        LongPressLayer(CellSelector selector) {
            this.selector = selector;
            dragThreshold = PixelScene.defaultZoom * DungeonTilemap.SIZE / 2f;
            PointerEvent.addPointerListener(this);
        }

        @Override
        public boolean onSignal(PointerEvent event) {
            if (!mapLongPressEnabledForHero()) {
                return false;
            }

            if (event == null) {
                if (press != null && movedTooFar()) {
                    cancelled = true;
                }
                return false;
            }

            if (event.type == PointerEvent.Type.DOWN) {
                if (press != null) {
                    // A second pointer means pinch/gesture input, never Assassinate.
                    cancelled = true;
                    return false;
                }

                if (event.button == PointerEvent.RIGHT
                        || event.button == PointerEvent.MIDDLE
                        || event.button == PointerEvent.BACK
                        || event.button == PointerEvent.FORWARD
                        || !normalMapMode(selector)
                        || selector.target == null
                        || !selector.target.overlapsScreenPoint(
                                (int) event.current.x, (int) event.current.y)) {
                    return false;
                }

                press = event;
                heldTime = 0f;
                cancelled = false;
                return false;
            }

            if (press != null && event == press
                    && (event.type == PointerEvent.Type.UP || isCancelEvent(event))) {
                clearPress();
            }

            return false;
        }

        @Override
        public void update() {
            super.update();

            if (!mapLongPressEnabledForHero()
                    || parent != ShatteredPixelDungeon.scene()
                    || currentCellSelector() != selector) {
                killAndErase();
                return;
            }

            if (press == null) {
                return;
            }

            if (cancelled || movedTooFar()) {
                clearPress();
                return;
            }

            heldTime += Game.elapsed;
            if (heldTime < Button.longClick) {
                return;
            }

            Hero hero = Dungeon.hero;
            if (!normalMapMode(selector)
                    || selectorEvent(selector) != press
                    || hero == null
                    || !hero.ready
                    || GameScene.interfaceBlockingHero()) {
                clearPress();
                return;
            }

            int cell = resolveCell(press.current);
            if (cell == -1) {
                clearPress();
                return;
            }

            // Cancel the ordinary CellSelector click before invoking Assassinate.
            selector.reset();
            clearPress();

            // Reuse the exact dispatcher already used by ModAssassin.cast().
            // All combat handling belongs to ModAssassin.perform(); this layer
            // is intentionally input-only so one long press can never hit twice.
            new ModAssassin.Selector(hero).onSelect(cell);
            GameScene.ready();
        }

        private boolean movedTooFar() {
            return press != null
                    && PointF.distance(press.current, press.start) > dragThreshold;
        }

        private int resolveCell(PointF screenPos) {
            if (screenPos == null || Dungeon.level == null || Dungeon.hero == null) {
                return -1;
            }

            PointF p = Camera.main.screenToCamera((int) screenPos.x, (int) screenPos.y);
            Hero hero = Dungeon.hero;

            if (hero.sprite != null && hero.sprite.overlapsPoint(p.x, p.y)) {
                PointF center = DungeonTilemap.tileCenterToWorld(hero.pos);
                if (Math.abs(p.x - center.x) <= 12 && Math.abs(p.y - center.y) <= 12) {
                    return hero.pos;
                }
            }

            for (Mob mob : Dungeon.level.mobs.toArray(new Mob[0])) {
                if (mob.sprite != null && mob.sprite.overlapsPoint(p.x, p.y)) {
                    PointF center = DungeonTilemap.tileCenterToWorld(mob.pos);
                    if (Math.abs(p.x - center.x) <= 12 && Math.abs(p.y - center.y) <= 12) {
                        return mob.pos;
                    }
                }
            }

            if (selector.target instanceof DungeonTilemap) {
                return ((DungeonTilemap) selector.target).screenToTile(
                        (int) screenPos.x, (int) screenPos.y, true);
            }

            return -1;
        }

        private void clearPress() {
            press = null;
            heldTime = 0f;
            cancelled = false;
        }

        @Override
        public void destroy() {
            PointerEvent.removePointerListener(this);
            clearPress();
            if (inputLayer == this) {
                inputLayer = null;
            }
            super.destroy();
        }
    }


    /**
     * Injection-safe edge Tag. It attaches itself to the active GameScene at
     * runtime instead of requiring a source or bytecode patch to GameScene.
     */
    private static class AssassinateTag extends Tag {

        private static final int COLOR = 0x6A407F;
        private static final String[] HUD_TAG_FIELDS = {"attack", "loot", "action", "resume"};
        private static final String[] HUD_TAG_STATE_FIELDS =
                {"tagAttack", "tagLoot", "tagAction", "tagResume"};

        private static AssassinateTag instance;
        private static AssassinateSelector selector;
        private static Char aimedTarget;
        private static boolean installPending;

        private static Field[] hudTagFields;
        private static Field[] hudTagStateFields;
        private static Field toolbarField;
        private static Field statusField;
        private static Field quickSlotLastTargetField;
        private static Method flipTagsMethod;
        private static Method interfaceSizeMethod;
        private static Method tagFlipMethod;

        private final Image icon;
        private final Image crosshair;
        private boolean leftSide;

        AssassinateTag() {
            super(COLOR);

            icon = Icons.TARGET.get();
            add(icon);

            crosshair = Icons.TARGET.get();

            setSize(SIZE, SIZE);
        }

        static void ensureInstalled() {
            if (!assassinEnabledForHero()) {
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
                    if (!assassinEnabledForHero()) {
                        return;
                    }

                    Group scene = (Group) ShatteredPixelDungeon.scene();
                    if (instance == null || !instance.exists || instance.parent != scene) {
                        instance = new AssassinateTag();
                        instance.camera = PixelScene.uiCamera;
                        scene.addToFront(instance);
                    }
                }
            });
        }

        static void disable() {
            ShatteredPixelDungeon.runOnRenderThread(new Callback() {
                @Override
                public void call() {
                    AssassinateTag tag = instance;
                    if (tag == null) {
                        clearSelectionState(null);
                        return;
                    }

                    if (tag.parent == ShatteredPixelDungeon.scene()) {
                        cancelSelection();
                    } else {
                        clearSelectionState(null);
                    }

                    if (tag.exists) {
                        tag.killAndErase();
                    }
                    if (instance == tag) {
                        instance = null;
                    }
                }
            });
        }

        @Override
        public void update() {
            if (!assassinEnabledForHero()
                    || parent != ShatteredPixelDungeon.scene()
                    || PixelScene.uiCamera == null) {
                if (parent == ShatteredPixelDungeon.scene()) {
                    cancelSelection();
                } else {
                    clearSelectionState(null);
                }
                killAndErase();
                if (instance == this) {
                    instance = null;
                }
                return;
            }

            layoutAgainstHud();
            refreshCrosshair();

            super.update();
            givePointerPriority();
        }

        @Override
        protected void onClick() {
            super.onClick();

            Hero hero = Dungeon.hero;
            if (!assassinEnabledForHero()
                    || hero == null
                    || !hero.ready
                    || GameScene.interfaceBlockingHero()) {
                return;
            }

            if (selector != null && !ownsCellSelector(selector)) {
                clearSelectionState(selector);
            }

            if (selector != null) {
                if (validAimTarget(aimedTarget)) {
                    GameScene.handleCell(aimedTarget.pos);
                } else {
                    setAimTarget(preferredTarget());
                }
                return;
            }

            if (GameScene.cancel()) {
                return;
            }

            AssassinateSelector newSelector = new AssassinateSelector(hero);
            selector = newSelector;
            GameScene.selectCell(newSelector);
            setAimTarget(preferredTarget());
            flash();
        }

        @Override
        protected String hoverText() {
            return "Assassinate";
        }

        @Override
        protected void layout() {
            super.layout();

            if (!leftSide) {
                icon.x = x + width - (SIZE + icon.width()) / 2f - 1f;
            } else {
                icon.x = x + (SIZE - icon.width()) / 2f + 1f;
            }
            icon.y = y + (height - icon.height()) / 2f;
            PixelScene.align(icon);
        }

        private void layoutAgainstHud() {
            Object scene = ShatteredPixelDungeon.scene();
            boolean left = readFlipTags();
            float tagWidth = SIZE;
            float tagLeft = left ? 0f : PixelScene.uiCamera.width - tagWidth;
            float pos = basePosition(scene, left);
            boolean foundNativeTag = false;

            try {
                ensureHudFields();
                for (int i = 0; i < HUD_TAG_FIELDS.length; i++) {
                    if (!hudTagStateFields[i].getBoolean(scene)) {
                        continue;
                    }

                    Object value = hudTagFields[i].get(scene);
                    if (!(value instanceof Tag)) {
                        continue;
                    }

                    Tag tag = (Tag) value;
                    if (!foundNativeTag) {
                        tagWidth = tag.width();
                        tagLeft = tag.left();
                        pos = tag.top();
                        left = tag.left() < PixelScene.uiCamera.width / 2f;
                        foundNativeTag = true;
                    } else {
                        pos = Math.min(pos, tag.top());
                    }
                }
            } catch (Exception ignored) {
                // Private HUD fields vary between forks. The edge fallback below
                // remains usable even when no native tag stack can be inspected.
            }

            leftSide = left;
            reflectFlip(left);
            setRect(tagLeft, pos - SIZE, tagWidth, SIZE);
        }

        private static void ensureHudFields() throws Exception {
            if (hudTagFields != null && hudTagStateFields != null) {
                return;
            }

            Field[] tags = new Field[HUD_TAG_FIELDS.length];
            Field[] states = new Field[HUD_TAG_STATE_FIELDS.length];
            for (int i = 0; i < HUD_TAG_FIELDS.length; i++) {
                tags[i] = GameScene.class.getDeclaredField(HUD_TAG_FIELDS[i]);
                tags[i].setAccessible(true);
                states[i] = GameScene.class.getDeclaredField(HUD_TAG_STATE_FIELDS[i]);
                states[i].setAccessible(true);
            }
            hudTagFields = tags;
            hudTagStateFields = states;
        }

        private static float basePosition(Object scene, boolean left) {
            float pos = PixelScene.uiCamera.height;

            try {
                if (toolbarField == null) {
                    toolbarField = GameScene.class.getDeclaredField("toolbar");
                    toolbarField.setAccessible(true);
                }
                Object toolbar = toolbarField.get(scene);
                if (toolbar instanceof Component) {
                    pos = ((Component) toolbar).top();
                }

                if (left && readInterfaceSize() > 0) {
                    if (statusField == null) {
                        statusField = GameScene.class.getDeclaredField("status");
                        statusField.setAccessible(true);
                    }
                    Object status = statusField.get(scene);
                    if (status instanceof Component) {
                        pos = ((Component) status).top();
                    }
                }
            } catch (Exception ignored) {
                // The bottom edge is the only safe generic fallback when a fork
                // changes GameScene's private HUD field names.
            }

            return Math.max(SIZE, pos);
        }

        private static boolean readFlipTags() {
            try {
                if (flipTagsMethod == null) {
                    flipTagsMethod = SPDSettings.class.getDeclaredMethod("flipTags");
                    flipTagsMethod.setAccessible(true);
                }
                Object result = flipTagsMethod.invoke(null);
                return result instanceof Boolean && (Boolean) result;
            } catch (Exception ignored) {
                return false;
            }
        }

        private static int readInterfaceSize() {
            try {
                if (interfaceSizeMethod == null) {
                    interfaceSizeMethod =
                            SPDSettings.class.getDeclaredMethod("interfaceSize");
                    interfaceSizeMethod.setAccessible(true);
                }
                Object result = interfaceSizeMethod.invoke(null);
                return result instanceof Number ? ((Number) result).intValue() : 0;
            } catch (Exception ignored) {
                return 0;
            }
        }

        private void reflectFlip(boolean left) {
            try {
                if (tagFlipMethod == null) {
                    tagFlipMethod = Tag.class.getMethod("flip", boolean.class);
                    tagFlipMethod.setAccessible(true);
                }
                tagFlipMethod.invoke(this, left);
            } catch (Exception ignored) {
                // Older forks without Tag.flip() still get a functional right-edge
                // tag; only the decorative chrome orientation differs.
            }
        }

        private void refreshCrosshair() {
            if (selector == null) {
                removeCrosshair();
                return;
            }

            if (!ownsCellSelector(selector)) {
                clearSelectionState(selector);
                return;
            }

            if (!validAimTarget(aimedTarget)) {
                setAimTarget(preferredTarget());
            } else {
                positionCrosshair();
            }
        }

        private static boolean ownsCellSelector(AssassinateSelector expected) {
            CellSelector cellSelector = currentCellSelector();
            return cellSelector != null && cellSelector.listener == expected;
        }

        private static Char preferredTarget() {
            Char lastTarget = quickSlotLastTarget();
            if (validAimTarget(lastTarget)) {
                return lastTarget;
            }

            Hero hero = Dungeon.hero;
            if (hero != null) {
                int visibleEnemies = hero.visibleEnemies();
                for (int i = 0; i < visibleEnemies; i++) {
                    Char target = hero.visibleEnemy(i);
                    if (validAimTarget(target)) {
                        return target;
                    }
                }
            }
            return null;
        }

        private static Char quickSlotLastTarget() {
            try {
                if (quickSlotLastTargetField == null) {
                    quickSlotLastTargetField =
                            QuickSlotButton.class.getDeclaredField("lastTarget");
                    quickSlotLastTargetField.setAccessible(true);
                }
                Object target = quickSlotLastTargetField.get(null);
                return target instanceof Char ? (Char) target : null;
            } catch (Exception ignored) {
                return null;
            }
        }

        private static boolean validAimTarget(Char target) {
            if (target == null
                    || target == Dungeon.hero
                    || !target.isAlive()
                    || target.alignment == Char.Alignment.ALLY
                    || Char.hasProp(target, Char.Property.OBJECT)
                    || Dungeon.level == null
                    || target.pos < 0
                    || target.pos >= Dungeon.level.heroFOV.length
                    || !Dungeon.level.heroFOV[target.pos]
                    || target.sprite == null
                    || target.sprite.parent == null) {
                return false;
            }
            return true;
        }

        private static void setAimTarget(Char target) {
            aimedTarget = target;
            if (instance == null) {
                return;
            }

            instance.removeCrosshair();
            aimedTarget = target;
            if (validAimTarget(target)) {
                target.sprite.parent.addToFront(instance.crosshair);
                instance.positionCrosshair();
            }
        }

        private void positionCrosshair() {
            if (validAimTarget(aimedTarget)) {
                crosshair.point(aimedTarget.sprite.center(crosshair));
            }
        }

        private void removeCrosshair() {
            if (crosshair.parent != null) {
                crosshair.remove();
            }
        }

        private static void cancelSelection() {
            AssassinateSelector current = selector;
            if (current == null) {
                if (instance != null) {
                    instance.removeCrosshair();
                }
                aimedTarget = null;
                return;
            }

            CellSelector cellSelector = currentCellSelector();
            if (cellSelector != null && cellSelector.listener == current) {
                cellSelector.cancel();
            } else {
                clearSelectionState(current);
            }
        }

        private static void clearSelectionState(AssassinateSelector expected) {
            if (expected != null && selector != expected) {
                return;
            }
            selector = null;
            aimedTarget = null;
            if (instance != null) {
                instance.removeCrosshair();
            }
        }

        private static class AssassinateSelector extends ModAssassin.Selector {

            AssassinateSelector(Hero hero) {
                super(hero);
            }

            @Override
            public void onSelect(Integer pos) {
                try {
                    super.onSelect(pos);
                } finally {
                    clearSelectionState(this);
                }
            }
        }
    }

    @Override
    public void storeInBundle(Bundle bundle) {
        super.storeInBundle(bundle);
        bundle.put(ASSASSIN_ENABLED, assassinEnabled);
        bundle.put(MAP_LONG_PRESS_ENABLED, mapLongPressEnabled);
    }

    @Override
    public void restoreFromBundle(Bundle bundle) {
        super.restoreFromBundle(bundle);
        assassinEnabled = !bundle.contains(ASSASSIN_ENABLED)
                || bundle.getBoolean(ASSASSIN_ENABLED);
        mapLongPressEnabled = bundle.contains(MAP_LONG_PRESS_ENABLED)
                && bundle.getBoolean(MAP_LONG_PRESS_ENABLED);
    }
}
