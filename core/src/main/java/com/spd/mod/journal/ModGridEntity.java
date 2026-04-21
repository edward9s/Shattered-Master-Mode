package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingGridPane;
import com.watabou.noosa.Image;
import com.watabou.utils.RectF;

public class ModGridEntity extends ScrollingGridPane.GridItem {

    private Class<?> entityClass;

    public ModGridEntity(Image image, Class<?> entityClass) {
        super(new Image(image));
        this.entityClass = entityClass;

        Image icon = this.icon;

        if (icon.width < 17.0f || icon.height < 17.0f) {
            RectF frame = icon.frame();

            float wShrink = frame.width() * (1.0f - (17.0f / icon.width));
            if (wShrink > 0.0f) {
                frame.left += wShrink / 2.0f;
                frame.right -= wShrink / 2.0f;
            }

            float hShrink = frame.height() * (1.0f - (17.0f / icon.height));
            if (hShrink > 0.0f) {
                frame.top += hShrink / 2.0f;
                frame.bottom -= hShrink / 2.0f;
            }

            icon.frame(frame);
        }
    }

    @Override
    public boolean onClick(float x, float y) {
        if (!inside(x, y)) {
            return false;
        }

        ModCellSelector.start(this.entityClass);
        return true;
    }
}
