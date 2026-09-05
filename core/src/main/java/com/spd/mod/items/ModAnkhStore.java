package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.InventorySlot;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoItem;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.Game;
import com.watabou.noosa.audio.Sample;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;
import com.watabou.utils.PointF;

import java.util.ArrayList;

/**
 * Self-contained storage and Put/Take UI for ModAnkh.
 * This class is a first-class APK-injection payload and has no ModDebug dependency.
 *
 * Take intentionally mirrors the proven WndModLoot grid interaction while remaining inside the
 * ModAnkhStore class family, so ModAnkh keeps its standalone payload boundary.
 */
public final class ModAnkhStore {

    private static final String STORED = "stored";

    private final ArrayList<Item> stored = new ArrayList<>();

    // UI-only state. Deliberately not serialized.
    private float takeScrollY = 0f;

    public void storeInBundle(Bundle bundle) {
        bundle.put(STORED, stored);
    }

    public void restoreFromBundle(Bundle bundle) {
        stored.clear();
        takeScrollY = 0f;
        for (Bundlable value : bundle.getCollection(STORED)) {
            if (value instanceof Item) {
                stored.add((Item) value);
            }
        }
    }

    public boolean isEmpty() {
        return stored.isEmpty();
    }

    public int size() {
        return stored.size();
    }

    public void showPutSelector(final Item owner, final Hero hero) {
        if (owner == null || hero == null || hero.belongings == null
                || hero.belongings.backpack == null) {
            return;
        }

        GameScene.selectItem(new WndBag.ItemSelector() {
            @Override
            public String textPrompt() {
                return "Select an item to store";
            }

            @Override
            public boolean itemSelectable(Item item) {
                return canStore(owner, item);
            }

            @Override
            public void onSelect(Item item) {
                if (item != null && putSingle(owner, hero, item)) {
                    showPutSelector(owner, hero);
                }
            }
        });
    }

    public void showTakeSelector(final Item owner, final Hero hero) {
        if (owner == null || hero == null || stored.isEmpty()) {
            takeScrollY = 0f;
            return;
        }
        GameScene.show(new WndTake(owner, hero));
    }

    private boolean canStore(Item owner, Item item) {
        return item != null
                && owner != null
                && item.getClass() != owner.getClass();
    }

    private boolean putSingle(Item owner, Hero hero, Item item) {
        if (hero == null || hero.belongings == null || hero.belongings.backpack == null
                || !canStore(owner, item)) {
            return false;
        }

        if (item.isEquipped(hero)) {
            if (!(item instanceof EquipableItem)
                    || !((EquipableItem) item).doUnequip(hero, false)) {
                GLog.w("Can't unequip selected item.");
                return false;
            }
        }

        Item detached = item.detachAll(hero.belongings.backpack);
        if (detached == null || detached.quantity() <= 0) {
            return false;
        }

        absorb(detached);
        GLog.i("Stored item in the ankh.");
        Sample.INSTANCE.play(Assets.Sounds.ITEM);
        Item.updateQuickslot();
        return true;
    }

    /** Matches ModLootStorage Take semantics: if bags are full, release the item at the hero. */
    private boolean takeItem(Hero hero, Item item) {
        if (hero == null || hero.belongings == null || hero.belongings.backpack == null
                || item == null || !stored.contains(item)) {
            return false;
        }

        if (item.collect(hero.belongings.backpack)) {
            stored.remove(item);
            GLog.i("Took item from the ankh.");
        } else {
            com.shatteredpixel.shatteredpixeldungeon.Dungeon.level
                    .drop(item, hero.pos).sprite.drop();
            stored.remove(item);
            GLog.w("Dropped item on the floor (backpack full).");
        }

        if (stored.isEmpty()) {
            takeScrollY = 0f;
        }
        Sample.INSTANCE.play(Assets.Sounds.ITEM);
        Item.updateQuickslot();
        return true;
    }

    private void absorb(Item item) {
        if (item.stackable) {
            for (Item existing : stored) {
                if (existing.isSimilar(item)) {
                    existing.merge(item);
                    return;
                }
            }
        }
        stored.add(item);
    }

    /** TAKE-only counterpart of WndModLoot, kept inside ModAnkhStore's injectable class family. */
    private final class WndTake extends Window {

        private static final int NCOLS = 5;
        private static final int SLOT_BASE = 28;
        private static final int SLOT_MARGIN = 1;
        private static final int TITLE_HEIGHT = 14;
        private static final int UI_RESERVE_VER = 100;

        private final Hero hero;
        private final ArrayList<Item> items = stored;

        private TakePane pane;
        private int paneX, paneY, paneW, paneH;
        private int slotSize;

        private float lastCamX = Float.NaN;
        private float lastCamY = Float.NaN;

        WndTake(Item owner, Hero hero) {
            super();
            this.hero = hero;

            slotSize = SLOT_BASE;
            int windowWidth = slotSize * NCOLS + SLOT_MARGIN * (NCOLS - 1);

            if (!PixelScene.landscape()) {
                while (slotSize >= 26
                        && (windowWidth + chrome.marginHor()) > PixelScene.uiCamera.width) {
                    slotSize--;
                    windowWidth -= NCOLS;
                }
            }

            int rows = Math.max(1, (int) Math.ceil(items.size() / (float) NCOLS));
            int contentHeight = rows * slotSize + (rows - 1) * SLOT_MARGIN;
            int maxWindowHeight = PixelScene.uiCamera.height - UI_RESERVE_VER - chrome.marginVer();
            int maxPaneHeight = maxWindowHeight - TITLE_HEIGHT;
            int paneHeight = Math.min(contentHeight, Math.max(slotSize, maxPaneHeight));

            placeTitle(owner, windowWidth);
            resize(windowWidth, TITLE_HEIGHT + paneHeight);

            paneX = 0;
            paneY = TITLE_HEIGHT;
            paneW = windowWidth;
            paneH = paneHeight;

            pane = new TakePane();
            add(pane);
            rebuild(takeScrollY);
        }

        @Override
        public synchronized void update() {
            super.update();
            if (pane != null && pane.content() != null && pane.content().camera != null) {
                takeScrollY = pane.content().camera.scroll.y;
            }
            if (camera() != null && (camera().x != lastCamX || camera().y != lastCamY)) {
                lastCamX = camera().x;
                lastCamY = camera().y;
                relayoutPane();
            }
        }

        @Override
        public void offset(int xOffset, int yOffset) {
            super.offset(xOffset, yOffset);
            relayoutPane();
        }

        private void relayoutPane() {
            if (pane != null) {
                pane.setRect(paneX, paneY, paneW, paneH);
            }
        }

        private void rebuild(float scrollY) {
            pane.reconcile(items);
            pane.setRect(paneX, paneY, paneW, paneH);
            pane.scrollTo(0, scrollY);
        }

        private void placeTitle(Item owner, int width) {
            String title = owner.name().concat(" (").concat(Integer.toString(size())).concat(")");
            RenderedTextBlock text = PixelScene.renderTextBlock(title, 8);
            text.hardlight(TITLE_COLOR);
            text.maxWidth(width - 2);
            text.setPos(1, (TITLE_HEIGHT - text.height()) / 2f - 1);
            PixelScene.align(text);
            add(text);
        }

        private void onSelect(Item item) {
            takeItem(hero, item);
            rebuild(takeScrollY);
        }

        private final class TakePane extends ScrollPane {

            private final ArrayList<Slot> slots = new ArrayList<>();

            TakePane() {
                super(new Component());
                remove(controller);
                controller.destroy();
                controller = new TakeController();
                add(controller);
            }

            void reconcile(ArrayList<Item> current) {
                int rows = Math.max(1, (int) Math.ceil(current.size() / (float) NCOLS));
                int total = rows * NCOLS;

                while (slots.size() < total) {
                    Slot slot = new Slot();
                    content.add(slot);
                    slots.add(slot);
                }
                while (slots.size() > total) {
                    Slot slot = slots.remove(slots.size() - 1);
                    content.remove(slot);
                    slot.destroy();
                }

                for (int i = 0; i < total; i++) {
                    slots.get(i).item(i < current.size() ? current.get(i) : null);
                }
            }

            @Override
            public void onClick(float x, float y) {
                for (Slot slot : slots) {
                    if (slot.onClick(x, y)) {
                        break;
                    }
                }
            }

            boolean onLongClick(float x, float y) {
                for (Slot slot : slots) {
                    if (slot.inside(x, y)) {
                        if (slot.item != null) {
                            ShatteredPixelDungeon.scene().addToFront(new WndInfoItem(slot.item));
                            return true;
                        }
                        return false;
                    }
                }
                return false;
            }

            private final class TakeController extends PointerController {

                private boolean pressing = false;
                private boolean longClicked = false;
                private float pressTime = 0f;
                private final PointF pressStart = new PointF();
                private final float pressDragThreshold = PixelScene.defaultZoom * 8;

                @Override
                protected void onPointerDown(PointerEvent event) {
                    super.onPointerDown(event);
                    pressing = true;
                    longClicked = false;
                    pressTime = 0f;
                    pressStart.set(event.current);
                }

                @Override
                protected void onPointerUp(PointerEvent event) {
                    super.onPointerUp(event);
                    pressing = false;
                }

                @Override
                protected void onDrag(PointerEvent event) {
                    if (longClicked) {
                        return;
                    }
                    if (pressing
                            && PointF.distance(event.current, pressStart) > pressDragThreshold) {
                        pressing = false;
                    }
                    super.onDrag(event);
                }

                @Override
                public void update() {
                    super.update();
                    if (pressing && (pressTime += Game.elapsed) >= Button.longClick) {
                        pressing = false;
                        PointF point = content.camera.screenToCamera(
                                (int) pressStart.x,
                                (int) pressStart.y);
                        if (TakePane.this.onLongClick(point.x, point.y)) {
                            longClicked = true;
                            if (SPDSettings.vibration()) {
                                Game.vibrate(50);
                            }
                        }
                    }
                }

                @Override
                protected void onClick(PointerEvent event) {
                    if (longClicked) {
                        longClicked = false;
                        return;
                    }
                    if (event.button == PointerEvent.RIGHT) {
                        PointF point = content.camera.screenToCamera(
                                (int) event.current.x,
                                (int) event.current.y);
                        TakePane.this.onLongClick(point.x, point.y);
                    } else {
                        super.onClick(event);
                    }
                }
            }

            @Override
            protected void layout() {
                int n = slots.size();
                int rows = n == 0 ? 0 : (int) Math.ceil(n / (float) NCOLS);

                for (int i = 0; i < n; i++) {
                    int col = i % NCOLS;
                    int row = i / NCOLS;
                    float sx = col * (slotSize + SLOT_MARGIN);
                    float sy = row * (slotSize + SLOT_MARGIN);
                    slots.get(i).setRect(sx, sy, slotSize, slotSize);
                }

                int contentHeight = rows == 0
                        ? 0
                        : rows * slotSize + (rows - 1) * SLOT_MARGIN;
                content.setSize(width, contentHeight);
                super.layout();
            }

            private final class Slot extends Component {

                private Item item;
                private final InventorySlot visual;

                Slot() {
                    super();
                    visual = new InventorySlot(null) {
                        {
                            remove(hotArea);
                        }
                    };
                    add(visual);
                }

                void item(Item item) {
                    if (this.item == item) {
                        return;
                    }
                    this.item = item;
                    visual.item(item);
                }

                @Override
                public synchronized void update() {
                    super.update();
                    if (item != null && visual.exists && !visual.active) {
                        visual.update();
                    }
                }

                @Override
                protected void layout() {
                    visual.setRect(x, y, width, height);
                }

                boolean onClick(float cx, float cy) {
                    if (!inside(cx, cy)) {
                        return false;
                    }
                    if (item != null) {
                        WndTake.this.onSelect(item);
                    }
                    return true;
                }
            }
        }
    }
}
