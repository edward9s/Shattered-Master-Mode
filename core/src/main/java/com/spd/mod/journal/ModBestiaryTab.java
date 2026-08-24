package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfWarding;
import com.shatteredpixel.shatteredpixeldungeon.journal.Bestiary;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.tiles.TerrainFeaturesTilemap;
import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.noosa.Image;
import com.watabou.noosa.ui.Component;
import com.watabou.utils.Reflection;

import java.util.ArrayList;
import java.util.Collection;

public class ModBestiaryTab extends Component {

    public static ModBestiaryTab instance;
    public static float scrollTop;

    private ScrollingGridPane grid;

    public ModBestiaryTab() {
        super();
        instance = this;

        grid = new ScrollingGridPane();
        add(grid);

        for (Bestiary bestiary : Bestiary.values()) {
            grid.addHeader(Messages.titleCase(bestiary.title()));

            Collection<Class<?>> entities = bestiary.entities();
            for (Class<?> clazz : entities) {
                ModGridEntity gridEntity = createGridEntity(clazz);
                if (gridEntity != null) {
                    grid.addItem(gridEntity);
                }
            }
        }

        addScannedSection("Uncatalogued Mobs", ModBestiaryExtras.mobs());
        addScannedSection("Uncatalogued Traps", ModBestiaryExtras.traps());
        addScannedSection("Uncatalogued Plants", ModBestiaryExtras.plants());
    }

    private void addScannedSection(String title, Iterable<? extends Class<?>> classes) {
        ArrayList<ModGridEntity> entities = new ArrayList<>();

        for (Class<?> clazz : classes) {
            ModGridEntity gridEntity = createGridEntity(clazz);
            if (gridEntity != null) {
                entities.add(gridEntity);
            }
        }

        if (!entities.isEmpty()) {
            grid.addHeader(title);
            for (ModGridEntity entity : entities) {
                grid.addItem(entity);
            }
        }
    }

    private ModGridEntity createGridEntity(Class<?> clazz) {
        try {
            Object entityInstance = Reflection.newInstance(clazz);
            Image image;

            if (entityInstance instanceof Mob) {
                Mob mob = (Mob) entityInstance;

                if (mob instanceof WandOfWarding.Ward) {
                    WandOfWarding.Ward ward = (WandOfWarding.Ward) mob;
                    if (ward instanceof WandOfWarding.Ward.WardSentry) {
                        ward.upgrade(3);
                        ward.upgrade(3);
                        ward.upgrade(3);
                        ward.upgrade(3);
                    } else {
                        ward.upgrade(0);
                    }
                }

                CharSprite sprite = mob.sprite();
                sprite.idle();
                image = sprite;

            } else if (entityInstance instanceof Trap) {
                Trap trap = (Trap) entityInstance;
                image = TerrainFeaturesTilemap.getTrapVisual(trap);

            } else if (entityInstance instanceof Plant) {
                Plant plant = (Plant) entityInstance;
                image = TerrainFeaturesTilemap.getPlantVisual(plant);

            } else {
                return null;
            }

            if (image == null) {
                return null;
            }
            return new ModGridEntity(image, clazz);

        } catch (Throwable ignore) {
            // Runtime discovery can include concrete helpers that still require
            // special constructor/game state. Skip those safely.
            return null;
        }
    }

    @Override
    public void update() {
        super.update();
        scrollTop = grid.content().camera.scroll.y;
    }

    @Override
    public void layout() {
        super.layout();
        grid.setRect(this.x, this.y, this.width, this.height);
    }

    public void restoreScroll() {
        grid.scrollTo(0f, scrollTop);
    }
}
