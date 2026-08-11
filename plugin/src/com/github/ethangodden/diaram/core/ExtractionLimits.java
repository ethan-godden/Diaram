package com.github.ethangodden.diaram.core;

/** Caps for the extraction BFS; a future preference page can build one from an IPreferenceStore. */
public record ExtractionLimits(
        int maxObjects,              // explored heap objects
        int maxDepth,                // reference hops from any root
        int maxFieldsPerObject,
        int maxArrayElements,
        int maxStaticFieldsPerClass,
        int maxStringLength,
        boolean inlineStrings,       // escape hatch: render strings inline instead of heap boxes
        boolean inlineBoxed,         // escape hatch: render boxed primitives inline
        boolean includeSyntheticFields) { // this$0, $assertionsDisabled, lambda captures

    public static ExtractionLimits defaults() {
        return new ExtractionLimits(200, 8, 32, 100, 24, 200, false, false, false);
    }

    /** Copy with a different explored-heap-object cap (the only per-view override). */
    public ExtractionLimits withMaxObjects(int newMaxObjects) {
        return new ExtractionLimits(newMaxObjects, maxDepth, maxFieldsPerObject,
                maxArrayElements, maxStaticFieldsPerClass, maxStringLength,
                inlineStrings, inlineBoxed, includeSyntheticFields);
    }
}
