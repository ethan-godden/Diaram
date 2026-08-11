package com.github.ethangodden.diaram.render;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Collapse state and per-owner "+N more" cap overrides, surviving full figure
 * rebuilds. Everything (frames, heap boxes, statics) is EXPANDED by default;
 * only user-collapsed items are remembered. Cleared when a new debug session
 * starts.
 */
public final class ExpansionMemory {

    private final Set<String> collapsedFrames = new HashSet<>();
    private final Set<String> collapsedObjects = new HashSet<>();
    private final Map<String, Integer> capOverrides = new HashMap<>();

    public boolean isFrameCollapsed(String frameKey) {
        return collapsedFrames.contains(frameKey);
    }

    public void setFrameCollapsed(String frameKey, boolean collapsed) {
        toggle(collapsedFrames, frameKey, collapsed);
    }

    public boolean isObjectCollapsed(String token) {
        return collapsedObjects.contains(token);
    }

    public void setObjectCollapsed(String token, boolean collapsed) {
        toggle(collapsedObjects, token, collapsed);
    }

    /** Back to the expanded-everywhere default. */
    public void expandAll() {
        collapsedFrames.clear();
        collapsedObjects.clear();
    }

    /** Render cap for one owner ("frame:key", "obj:id", "arr:id", "statics:class", "heap"). */
    public int capOf(String ownerKey, int defaultCap) {
        Integer override = capOverrides.get(ownerKey);
        return override != null ? override.intValue() : defaultCap;
    }

    /** A clicked "+N more" row lifts the owner's cap entirely. */
    public void raiseCap(String ownerKey) {
        capOverrides.put(ownerKey, Integer.valueOf(Integer.MAX_VALUE));
    }

    /** Drops the overrides of every owner key with the given prefix (or the exact key). */
    public void clearCaps(String ownerKeyPrefix) {
        capOverrides.keySet().removeIf(k -> k.startsWith(ownerKeyPrefix));
    }

    public void clear() {
        expandAll();
        capOverrides.clear();
    }

    /** Add {@code key} to {@code set} when {@code present}, else remove it. */
    private static <T> void toggle(Set<T> set, T key, boolean present) {
        if (present) {
            set.add(key);
        } else {
            set.remove(key);
        }
    }
}
