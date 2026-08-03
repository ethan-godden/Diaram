package com.github.ethangodden.debugmemoryview.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.github.ethangodden.debugmemoryview.model.MemorySnapshot;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableStruct;

/**
 * Deterministic single-column ordering of the heap: structs stack vertically in
 * the snapshot's discovery order ({@link MemorySnapshot#heap()}, already emitted
 * statics-first then BFS order by the adapter), with {@link LayoutMemory}
 * keeping previously seen structs in their remembered positions (sticky orderKey)
 * — existing ids never move, new ids append, evicted ids drop.
 *
 * PURE: imports from eclipseview.model and the JDK only (headless-testable).
 */
public final class HeapLayouter {

    private HeapLayouter() {
    }

    /** Returns the heap column: struct ids ordered by the sticky orderKey. */
    public static List<String> assign(MemorySnapshot snapshot, LayoutMemory memory) {
        Set<String> live = new HashSet<>();
        for (DisplayableStruct struct : snapshot.heap()) {
            live.add(struct.id());
        }
        memory.retainAll(live);

        List<String> ordered = new ArrayList<>(live.size());
        for (DisplayableStruct struct : snapshot.heap()) {
            String id = struct.id();
            memory.assign(id);
            ordered.add(id);
        }
        ordered.sort(Comparator.comparingLong(id -> memory.orderKeyOf(id).longValue()));
        return ordered;
    }
}
