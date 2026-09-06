package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Runtime adapters for fork/minifier-sensitive journal presentation members. */
final class ModJournalCompat {

    private ModJournalCompat() {
    }

    static ItemSprite holderIcon(String preferred, String... fallbacks) {
        Integer image = findStaticInt(preferred);
        if (image == null && fallbacks != null) {
            for (String fallback : fallbacks) {
                image = findStaticInt(fallback);
                if (image != null) {
                    break;
                }
            }
        }

        // The icon is cosmetic. A missing/renamed holder constant must not make
        // the journal, or the whole injected payload, unusable.
        return new ItemSprite(image != null ? image : 0, null);
    }

    private static Integer findStaticInt(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            Field field = ItemSpriteSheet.class.getDeclaredField(name);
            if (field.getType() != Integer.TYPE
                    || !Modifier.isStatic(field.getModifiers())) {
                return null;
            }
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
