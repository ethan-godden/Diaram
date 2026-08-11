package com.github.ethangodden.diaram.render;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.Nullable;

/**
 * Sticky heap-diagram order keys that survive full figure rebuilds so boxes
 * never jump between debugger steps. Cleared when a new debug session starts.
 *
 * PURE: imports from the JDK only (headless-testable); no Draw2d/SWT.
 */
public final class LayoutMemory {

    private final Map<String, Long> orderKeys = new HashMap<>();
    private long nextOrderKey;

    /**
     * A token already remembered keeps its orderKey verbatim (even if its
     * discovery position changed); a new token appends below every existing box.
     */
    public void assign(String token) {
        orderKeys.computeIfAbsent(token, key -> Long.valueOf(nextOrderKey++));
    }

    /** The remembered orderKey, or null for a token never assigned (or evicted). */
    public @Nullable Long orderKeyOf(String token) {
        return orderKeys.get(token);
    }

    /** Evicts orderKeys of tokens absent from the latest snapshot. */
    public void retainAll(Set<String> liveTokens) {
        orderKeys.keySet().retainAll(liveTokens);
    }

    public void clear() {
        orderKeys.clear();
        nextOrderKey = 0;
    }
}
