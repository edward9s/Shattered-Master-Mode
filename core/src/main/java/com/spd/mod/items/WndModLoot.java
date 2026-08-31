package com.spd.mod.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.InventorySlot;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndInfoItem;
import com.spd.mod.mechanics.ModLootStorage;
import com.watabou.input.PointerEvent;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Game;
import com.watabou.noosa.Image;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.PointF;

import java.util.ArrayList;

/** Scrollable shared Loot-storage window used by both Scroll of Loot and Loot buff. */
public class WndModLoot extends Window {

    public enum Mode {
        USE,
        TAKE
    }

    private static final int NCOLS = 5;
    private static final int SLOT_BASE = 28;
    private static final int SLOT_MARGIN = 1;
    private static final int TITLE_HEIGHT = 14;
    private static final int BTN_HEIGHT = 16;
    private static final int BTN_MARGIN = 1;
    private static final int UI_RESERVE_VER = 100;

    private final ModLootStorage storage;
    private final String title;
    private final Mode mode;

    private ArrayList<Item> items;

    private LootPane pane;
    private int paneX, paneY, paneW, paneH;
    private int slotSize;

    private static float lastScrollYUse = 0f;
    private static float lastScrollYTake = 0f;

    private float lastCamX = Float.NaN;
    private float lastCamY = Float.NaN;

    public WndModLoot(ModLootStorage storage, String title) {
        this(storage, title, Mode.TAKE);
    }

    public WndModLoot(ModLootStorage storage, String title, Mode mode) {
        super();
        this.storage = storage;
        this.title = title == null ? "Loot" : title;
        this.mode = mode;

        slotSize = SLOT_BASE;
        int windowWidth = slotSize * NCOLS + SLOT_MARGIN * (NCOLS - 1);

        if (!PixelScene.landscape()) {
            while (slotSize >= 26 && (windowWidth + chrome.marginHor()) > PixelScene.uiCamera.width) {
                slotSize--;
                windowWidth -= NCOLS;
            }
        }

        items = collectItems();

        int headerHeight = TITLE_HEIGHT;
        if (mode == Mode.USE) {
            headerHeight += 2 * (BTN_HEIGHT + BTN_MARGIN);
        }

        int rows = Math.max(1, (int) Math.ceil(items.size() / (float) NCOLS));
        int contentHeight = rows * slotSize + (rows - 1) * SLOT_MARGIN;

        int maxWindowHeight = PixelScene.uiCamera.height - UI_RESERVE_VER - chrome.marginVer();
        int maxPaneHeight = maxWindowHeight - headerHeight;
        int paneHeight = Math.min(contentHeight, Math.max(slotSize, maxPaneHeight));

        placeTitle(windowWidth);
        if (mode == Mode.USE) {
            placeButtons(windowWidth);
        }

        resize(windowWidth, headerHeight + paneHeight);

        paneX = 0;
        paneY = headerHeight;
        paneW = windowWidth;
        paneH = paneHeight;

        pane = new LootPane();
        add(pane);
        rebuild(rememberedScrollY());
    }

    private ArrayList<Item> collectItems() {
        if (mode == Mode.TAKE) {
            return storage.getStored();
        }
        return storage.getUsable(Dungeon.hero);
    }

    private void placeButtons(int width) {
        final int stored = storage.size();
        float half = width / 2f;
        float top = TITLE_HEIGHT;

        RedButton loot = new RedButton("Loot", 8) {
            @Override
            protected void onClick() {
                hide();
                storage.loot(Dungeon.hero);
            }
        };
        loot.setSize(half, BTN_HEIGHT);
        loot.setPos(0, top);
        add(loot);

        RedButton put = new RedButton("Put", 8) {
            @Override
            protected void onClick() {
                hide();
                showPutSelector();
            }
        };
        put.setSize(half, BTN_HEIGHT);
        put.setPos(half, top);
        add(put);

        top += BTN_HEIGHT + BTN_MARGIN;

        RedButton take = new RedButton("Take (" + stored + ")", 8) {
            @Override
            protected void onClick() {
                hide();
                GameScene.show(new WndModLoot(storage, title, Mode.TAKE));
            }
        };
        take.setSize(half, BTN_HEIGHT);
        take.setPos(0, top);
        take.enable(stored > 0);
        add(take);

        RedButton dump = new RedButton("Dump (" + stored + ")", 8) {
            @Override
            protected void onClick() {
                hide();
                storage.dump(Dungeon.hero);
            }
        };
        dump.setSize(half, BTN_HEIGHT);
        dump.setPos(half, top);
        dump.enable(stored > 0);
        add(dump);
    }

    private void showPutSelector() {
        GameScene.selectItem(new WndBag.ItemSelector() {
            @Override
            public String textPrompt() {
                return "Select an item to store";
            }

            @Override
            public boolean itemSelectable(Item item) {
                return ModLootStorage.canStore(item);
            }

            @Override
            public void onSelect(Item item) {
                if (item == null) {
                    return;
                }
                storage.putSingle(Dungeon.hero, item);
                showPutSelector();
            }
        });
    }

    @Override
    public synchronized void update() {
        super.update();
        if (pane != null && pane.content() != null && pane.content().camera != null) {
            rememberScrollY(pane.content().camera.scroll.y);
        }
        if (camera() != null && (camera().x != lastCamX || camera().y != lastCamY)) {
            lastCamX = camera().x;
            lastCamY = camera().y;
            relayoutPane();
        }
    }

    private void rememberScrollY(float y) {
        if (mode == Mode.USE) {
            lastScrollYUse = y;
        } else {
            lastScrollYTake = y;
        }
    }

    private float rememberedScrollY() {
        return mode == Mode.USE ? lastScrollYUse : lastScrollYTake;
    }

    private void onSelect(Item item) {
        if (mode == Mode.TAKE) {
            storage.takeItem(Dungeon.hero, item);
            rebuild(rememberedScrollY());
            return;
        }

        Hero hero = Dungeon.hero;
        if (hero == null || !hero.isAlive() || !storage.getStored().contains(item)) {
            ShatteredPixelDungeon.scene().addToFront(new WndInfoItem(item));
            return;
        }

        hide();
        storage.useItem(hero, item);
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

    private void placeTitle(int width) {
        float titleWidth;

        if (Dungeon.energy == 0) {
            ItemSprite gold = new ItemSprite(ItemSpriteSheet.GOLD, null);
            gold.x = width - gold.width();
            gold.y = (TITLE_HEIGHT - gold.height()) / 2f;
            PixelScene.align(gold);
            add(gold);

            BitmapText amt = new BitmapText(Integer.toString(Dungeon.gold), PixelScene.pixelFont);
            amt.hardlight(TITLE_COLOR);
            amt.measure();
            amt.x = width - gold.width() - amt.width() - 1;
            amt.y = (TITLE_HEIGHT - amt.baseLine()) / 2f - 1;
            PixelScene.align(amt);
            add(amt);

            titleWidth = amt.x;
        } else {
            Image gold = Icons.get(Icons.COIN_SML);
            gold.x = width - gold.width() - 0.5f;
            gold.y = 0;
            PixelScene.align(gold);
            add(gold);

            BitmapText amt = new BitmapText(Integer.toString(Dungeon.gold), PixelScene.pixelFont);
            amt.hardlight(TITLE_COLOR);
            amt.measure();
            amt.x = width - gold.width() - amt.width() - 2f;
            amt.y = 0;
            PixelScene.align(amt);
            add(amt);

            titleWidth = amt.x;

            Image energy = Icons.get(Icons.ENERGY_SML);
            energy.x = width - energy.width();
            energy.y = gold.height();
            PixelScene.align(energy);
            add(energy);

            amt = new BitmapText(Integer.toString(Dungeon.energy), PixelScene.pixelFont);
            amt.hardlight(0x44CCFF);
            amt.measure();
            amt.x = width - energy.width() - amt.width() - 1;
            amt.y = energy.y;
            PixelScene.align(amt);
            add(amt);

            titleWidth = Math.min(titleWidth, amt.x);
        }

        String displayTitle = mode == Mode.USE
                ? title
                : title + " (" + storage.size() + ")";
        RenderedTextBlock txtTitle = PixelScene.renderTextBlock(Messages.titleCase(displayTitle), 8);
        txtTitle.hardlight(TITLE_COLOR);
        txtTitle.maxWidth((int) titleWidth - 2);
        txtTitle.setPos(1, (TITLE_HEIGHT - txtTitle.height()) / 2f - 1);
        PixelScene.align(txtTitle);
        add(txtTitle);
    }

    private class LootPane extends ScrollPane {

        private final ArrayList<Slot> slots = new ArrayList<>();

        LootPane() {
            super(new Component());
            remove(controller);
            controller.destroy();
            controller = new LootController();
            add(controller);
        }

        void reconcile(ArrayList<Item> stored) {
            int rows = Math.max(1, (int) Math.ceil(stored.size() / (float) NCOLS));
            int total = rows * NCOLS;

            while (slots.size() < total) {
                Slot s = new Slot();
                content.add(s);
                slots.add(s);
            }
            while (slots.size() > total) {
                Slot s = slots.remove(slots.size() - 1);
                content.remove(s);
                s.destroy();
            }

            for (int i = 0; i < total; i++) {
                Item item = i < stored.size() ? stored.get(i) : null;
                slots.get(i).item(item);
            }
        }

        @Override
        public void onClick(float x, float y) {
            for (Slot s : slots) {
                if (s.onClick(x, y)) {
                    break;
                }
            }
        }

        boolean onLongClick(float x, float y) {
            for (Slot s : slots) {
                if (s.inside(x, y)) {
                    if (s.item != null) {
                        ShatteredPixelDungeon.scene().addToFront(new WndInfoItem(s.item));
                        return true;
                    }
                    return false;
                }
            }
            return false;
        }

        private class LootController extends PointerController {

            private boolean pressing = false;
            private boolean longClicked = false;
            private float pressTime = 0;
            private final PointF pressStart = new PointF();
            private final float pressDragThreshold = PixelScene.defaultZoom * 8;

            @Override
            protected void onPointerDown(PointerEvent event) {
                super.onPointerDown(event);
                pressing = true;
                longClicked = false;
                pressTime = 0;
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
                if (pressing && PointF.distance(event.current, pressStart) > pressDragThreshold) {
                    pressing = false;
                }
                super.onDrag(event);
            }

            @Override
            public void update() {
                super.update();
                if (pressing && (pressTime += Game.elapsed) >= Button.longClick) {
                    pressing = false;
                    PointF p = content.camera.screenToCamera((int) pressStart.x, (int) pressStart.y);
                    if (LootPane.this.onLongClick(p.x, p.y)) {
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
                    PointF p = content.camera.screenToCamera((int) event.current.x, (int) event.current.y);
                    LootPane.this.onLongClick(p.x, p.y);
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

            int contentHeight = rows == 0 ? 0 : rows * slotSize + (rows - 1) * SLOT_MARGIN;
            content.setSize(width, contentHeight);
            super.layout();
        }

        private class Slot extends Component {

            Item item;
            private InventorySlot visual;

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
                if (item != null && visual != null && visual.exists && !visual.active) {
                    visual.update();
                }
            }

            @Override
            protected void layout() {
                if (visual != null) {
                    visual.setRect(x, y, width, height);
                }
            }

            boolean onClick(float cx, float cy) {
                if (!inside(cx, cy)) {
                    return false;
                }
                if (item != null) {
                    WndModLoot.this.onSelect(item);
                }
                return true;
            }
        }
    }
}
