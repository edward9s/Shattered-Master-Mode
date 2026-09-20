package com.spd.mod.mechanics;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.tiles.DungeonTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon;
import com.shatteredpixel.shatteredpixeldungeon.ui.BuffIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.QuickSlotButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Tag;
import com.spd.mod.journal.ModRuntimeTagStack;
import com.spd.mod.journal.ModTotalInfoOverlay;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.Camera;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.Game;
import com.watabou.noosa.Gizmo;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.PointerArea;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;
import com.watabou.utils.PointF;
import com.watabou.utils.Signal;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Permanent Hero buff that exposes Assassinate through an edge Tag and an
 * optional map long-press gesture.
 */
public class ModAssassinate extends Buff {

    private static final String ASSASSIN_ENABLED = "assassin_enabled";
    private static final String MAP_LONG_PRESS_ENABLED = "map_long_press_enabled";
    private static final int ASSASSIN_PURPLE = 0xB06CFF;

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
        icon.hardlight(assassinEnabled ? ASSASSIN_PURPLE : 0xAAAAAA);
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
        private static final int TARGETING_BORDER_COLOR = 0xFF929292;

        private static AssassinateTag instance;
        private static AssassinateSelector selector;
        private static Char aimedTarget;
        private static boolean installPending;

        private static Field quickSlotLastTargetField;

        private final Image icon;
        private final Image crosshair;
        private final ColorBlock[] targetingBorder = new ColorBlock[4];

        AssassinateTag() {
            super(COLOR);

            for (int i = 0; i < targetingBorder.length; i++) {
                targetingBorder[i] = new ColorBlock(1, 1, TARGETING_BORDER_COLOR);
                targetingBorder[i].visible = false;
                add(targetingBorder[i]);
            }

            icon = preparationActionIcon();
            add(icon);

            crosshair = Icons.TARGET.get();

            setSize(SIZE, SIZE);
        }

        private static Image preparationActionIcon() {
            // Official Preparation's action button uses HeroIcon.PREPARATION,
            // not BuffIndicator.PREPARATION. Resolve it reflectively so binary
            // injection can still fall back cleanly on older SPD-family forks.
            try {
                String uiPackage = Tag.class.getPackage().getName();
                Class<?> heroIconClass = Class.forName(uiPackage + ".HeroIcon");
                Field preparationField = heroIconClass.getField("PREPARATION");
                final int preparationIcon = preparationField.getInt(null);

                for (Constructor<?> constructor : heroIconClass.getConstructors()) {
                    Class<?>[] params = constructor.getParameterTypes();
                    if (params.length != 1
                            || !params[0].isInterface()
                            || !params[0].getName().endsWith("ActionIndicator$Action")) {
                        continue;
                    }

                    Object action = Proxy.newProxyInstance(
                            params[0].getClassLoader(),
                            new Class<?>[]{params[0]},
                            (proxy, method, args) -> {
                                if ("actionIcon".equals(method.getName())) {
                                    return preparationIcon;
                                }
                                Class<?> type = method.getReturnType();
                                if (type == boolean.class) return false;
                                if (type == byte.class) return (byte) 0;
                                if (type == short.class) return (short) 0;
                                if (type == int.class) return 0;
                                if (type == long.class) return 0L;
                                if (type == float.class) return 0f;
                                if (type == double.class) return 0d;
                                if (type == char.class) return (char) 0;
                                return null;
                            });

                    Object value = constructor.newInstance(action);
                    if (value instanceof Image) {
                        Image result = (Image) value;
                        result.hardlight(ASSASSIN_PURPLE);
                        return result;
                    }
                }
            } catch (Exception ignored) {
                // Old or heavily modified forks may lack the action-specific
                // HeroIcon frame. Keep the feature usable with the buff icon.
            }

            BuffIcon fallback = new BuffIcon(new ModAssassinate(), true);
            fallback.hardlight(ASSASSIN_PURPLE);
            return fallback;
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
                        ModRuntimeTagStack.register(instance, 20);
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

                    ModRuntimeTagStack.unregister(tag);
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
                ModRuntimeTagStack.unregister(this);
                killAndErase();
                if (instance == this) {
                    instance = null;
                }
                return;
            }

            ModRuntimeTagStack.layout();
            refreshCrosshair();
            refreshTargetingBorder();

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
        }

        @Override
        protected String hoverText() {
            return "Assassinate";
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

            float left = x + 1f;
            float top = y + 1f;
            float innerWidth = Math.max(1f, width - 2f);
            float innerHeight = Math.max(1f, height - 2f);

            targetingBorder[0].x = left;
            targetingBorder[0].y = top;
            targetingBorder[0].size(innerWidth, 1f);

            targetingBorder[1].x = left;
            targetingBorder[1].y = y + height - 2f;
            targetingBorder[1].size(innerWidth, 1f);

            targetingBorder[2].x = left;
            targetingBorder[2].y = top;
            targetingBorder[2].size(1f, innerHeight);

            targetingBorder[3].x = x + width - 2f;
            targetingBorder[3].y = top;
            targetingBorder[3].size(1f, innerHeight);
        }

        private void refreshTargetingBorder() {
            boolean targeting = selector != null && ownsCellSelector(selector);
            for (ColorBlock edge : targetingBorder) {
                edge.visible = targeting;
            }
        }

        @Override
        public void destroy() {
            ModRuntimeTagStack.unregister(this);
            if (instance == this) {
                instance = null;
            }
            super.destroy();
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
