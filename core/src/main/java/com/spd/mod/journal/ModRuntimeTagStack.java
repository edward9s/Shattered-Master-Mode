package com.spd.mod.journal;

import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Tag;
import com.watabou.noosa.ui.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Shared layout for SMM edge tags that are attached to GameScene at runtime.
 *
 * Native/fork GameScene tags are discovered reflectively, then SMM runtime tags
 * are stacked above them in a deterministic order. Runtime tags never need to
 * know which other SMM features or third-party mods are installed.
 */
public final class ModRuntimeTagStack {

    private static final ArrayList<Entry> runtimeTags = new ArrayList<>();

    private static Field[] nativeTagFields;
    private static Field[] nativeTagStateFields;
    private static Field toolbarField;
    private static Field alternateToolbarField;
    private static boolean alternateToolbarResolved;
    private static Field statusField;
    private static Method flipTagsMethod;
    private static Method interfaceSizeMethod;
    private static Method tagFlipMethod;
    private static Method showingWindowMethod;
    private static boolean showingWindowResolved;

    private ModRuntimeTagStack() {
    }

    public static synchronized void register(Tag tag, int order) {
        if (tag == null) {
            return;
        }
        for (Entry entry : runtimeTags) {
            if (entry.tag == tag) {
                entry.order = order;
                return;
            }
        }
        runtimeTags.add(new Entry(tag, order));
    }

    public static synchronized void unregister(Tag tag) {
        if (tag == null) {
            return;
        }
        Iterator<Entry> iterator = runtimeTags.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().tag == tag) {
                iterator.remove();
                return;
            }
        }
    }

    public static synchronized void layout() {
        if (!(ShatteredPixelDungeon.scene() instanceof GameScene)
                || PixelScene.uiCamera == null) {
            return;
        }

        Object scene = ShatteredPixelDungeon.scene();
        cleanup(scene);
        if (runtimeTags.isEmpty()) {
            return;
        }

        boolean left = readFlipTags();
        float tagWidth = Tag.SIZE;
        float tagLeft = left ? 0f : PixelScene.uiCamera.width - tagWidth;
        float pos = basePosition(scene, left);
        boolean foundNativeTag = false;

        try {
            ensureNativeTagFields();
            for (int i = 0; i < nativeTagFields.length; i++) {
                Object value = nativeTagFields[i].get(scene);
                if (!(value instanceof Tag)) {
                    continue;
                }

                Tag tag = (Tag) value;
                if (isRuntimeTag(tag)) {
                    continue;
                }

                Field stateField = nativeTagStateFields[i];
                boolean inStack = stateField != null
                        ? stateField.getBoolean(scene)
                        : tag.visible && tag.active;
                if (!inStack) {
                    continue;
                }

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
            // A fork may change private GameScene fields. The generic edge
            // fallback below still gives runtime tags a usable location.
        }

        Collections.sort(runtimeTags, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Integer.compare(a.order, b.order);
            }
        });

        for (Entry entry : runtimeTags) {
            Tag tag = entry.tag;
            if (!tag.visible || !tag.exists || tag.parent != scene) {
                continue;
            }
            reflectFlip(tag, left);
            tag.setRect(tagLeft, pos - Tag.SIZE, tagWidth, Tag.SIZE);
            pos = tag.top();
        }
    }

    private static void cleanup(Object scene) {
        Iterator<Entry> iterator = runtimeTags.iterator();
        while (iterator.hasNext()) {
            Tag tag = iterator.next().tag;
            if (tag == null || !tag.exists || tag.parent != scene) {
                iterator.remove();
            }
        }
    }

    private static boolean isRuntimeTag(Tag tag) {
        for (Entry entry : runtimeTags) {
            if (entry.tag == tag) {
                return true;
            }
        }
        return false;
    }

    private static void ensureNativeTagFields() {
        if (nativeTagFields != null && nativeTagStateFields != null) {
            return;
        }

        List<Field> tags = new ArrayList<>();
        List<Field> states = new ArrayList<>();

        for (Field field : GameScene.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || !Tag.class.isAssignableFrom(field.getType())) {
                continue;
            }

            field.setAccessible(true);
            tags.add(field);

            Field state = null;
            String name = field.getName();
            if (!name.isEmpty()) {
                String stateName = "tag"
                        + Character.toUpperCase(name.charAt(0))
                        + name.substring(1);
                try {
                    state = GameScene.class.getDeclaredField(stateName);
                    if (state.getType() == boolean.class) {
                        state.setAccessible(true);
                    } else {
                        state = null;
                    }
                } catch (NoSuchFieldException ignored) {
                    // Third-party tags may not keep a parallel tag-state flag.
                }
            }
            states.add(state);
        }

        nativeTagFields = tags.toArray(new Field[0]);
        nativeTagStateFields = states.toArray(new Field[0]);
    }

    private static float basePosition(Object scene, boolean left) {
        float pos = PixelScene.uiCamera.height;

        Component toolbar = findToolbar(scene);
        if (toolbar != null) {
            pos = toolbar.top();
        }

        if (left && readInterfaceSize() > 0) {
            try {
                if (statusField == null) {
                    statusField = GameScene.class.getDeclaredField("status");
                    statusField.setAccessible(true);
                }
                Object status = statusField.get(scene);
                if (status instanceof Component) {
                    pos = ((Component) status).top();
                }
            } catch (Exception ignored) {
                // Keep the live toolbar position when a fork renames/removes status.
            }
        }

        return Math.max(Tag.SIZE, pos);
    }

    private static Component findToolbar(Object scene) {
        // Stock SPD keeps its toolbar in GameScene.toolbar. Some forks retain that
        // field but leave it null while an alternate toolbar (for example toolbarv1)
        // is active, so a successful field lookup is not enough.
        try {
            if (toolbarField == null) {
                toolbarField = GameScene.class.getDeclaredField("toolbar");
                toolbarField.setAccessible(true);
            }
            Object toolbar = toolbarField.get(scene);
            if (toolbar instanceof Component) {
                return (Component) toolbar;
            }
        } catch (Exception ignored) {
            // Fall through to fork toolbar discovery.
        }

        // Do not link against fork-specific toolbar classes or field names.
        // Resolve one alternate toolbar field once, then reuse it.
        if (!alternateToolbarResolved) {
            alternateToolbarResolved = true;
            for (Field field : GameScene.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || field == toolbarField
                        || !field.getName().toLowerCase().startsWith("toolbar")
                        || !Component.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(scene);
                    if (value instanceof Component) {
                        alternateToolbarField = field;
                        return (Component) value;
                    }
                } catch (Exception ignored) {
                    // Keep scanning other candidate toolbar fields.
                }
            }
        }

        if (alternateToolbarField != null) {
            try {
                Object value = alternateToolbarField.get(scene);
                if (value instanceof Component) {
                    return (Component) value;
                }
            } catch (Exception ignored) {
                // Fall through to the screen-edge fallback.
            }
        }
        return null;
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
                interfaceSizeMethod = SPDSettings.class.getDeclaredMethod("interfaceSize");
                interfaceSizeMethod.setAccessible(true);
            }
            Object result = interfaceSizeMethod.invoke(null);
            return result instanceof Number ? ((Number) result).intValue() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    public static boolean hasOpenWindow() {
        if (!(ShatteredPixelDungeon.scene() instanceof GameScene)) {
            return false;
        }

        if (!showingWindowResolved) {
            showingWindowResolved = true;
            try {
                showingWindowMethod = GameScene.class.getDeclaredMethod("showingWindow");
                showingWindowMethod.setAccessible(true);
            } catch (ReflectiveOperationException | SecurityException ignored) {
                showingWindowMethod = null;
            }
        }

        if (showingWindowMethod == null) {
            return false;
        }

        try {
            Object result = showingWindowMethod.invoke(null);
            return result instanceof Boolean && (Boolean) result;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static void reflectFlip(Tag tag, boolean left) {
        try {
            if (tagFlipMethod == null) {
                tagFlipMethod = Tag.class.getMethod("flip", boolean.class);
                tagFlipMethod.setAccessible(true);
            }
            tagFlipMethod.invoke(tag, left);
        } catch (Exception ignored) {
            // Older forks without Tag.flip() still get a functional right-edge
            // stack; only the chrome orientation differs.
        }
    }

    private static final class Entry {
        final Tag tag;
        int order;

        Entry(Tag tag, int order) {
            this.tag = tag;
            this.order = order;
        }
    }
}
