package com.github.ethangodden.diaram.render;

import org.apache.commons.lang3.StringUtils;

import com.github.ethangodden.diaram.model.MemorySnapshot.Value;

/** Text truncation and row-text composition helpers. */
public final class Ellipsis {

    public static final String ELLIPSIS = "…";

    private Ellipsis() {
    }

    /**
     * Display text of a value, char-capped. A {@link Value.BoxValue} renders its string ("null"
     * for the debuggee's null); a reference renders "→" (the arrow carries the target).
     */
    public static String valueText(Value value, int maxChars) {
        // abbreviate's width includes the marker, so maxChars + 1 keeps "maxChars chars + …".
        return StringUtils.abbreviate(fullValueText(value), ELLIPSIS, maxChars + 1);
    }

    /** Untruncated display text of a value (tooltips / previews). */
    public static String fullValueText(Value value) {
        return switch (value) {
            case Value.BoxValue primitive -> primitive.value();
            case Value.Reference reference -> "→"; // the arrow carries the target
        };
    }
}
