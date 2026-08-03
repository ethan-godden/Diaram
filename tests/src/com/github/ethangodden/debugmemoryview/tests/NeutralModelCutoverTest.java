package com.github.ethangodden.debugmemoryview.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.ethangodden.debugmemoryview.model.MemorySnapshot;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableFrame;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableStruct;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableThread;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableVariable;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.Value;
import com.github.ethangodden.debugmemoryview.model.diff.ChangeStatus;
import com.github.ethangodden.debugmemoryview.model.diff.DiffEngine;
import com.github.ethangodden.debugmemoryview.model.diff.MemoryDiff;
import com.github.ethangodden.debugmemoryview.render.HeapLayouter;
import com.github.ethangodden.debugmemoryview.render.LayoutMemory;

/**
 * Cross-cutting cutover tests, all JDK-only (no debugger, no Java-specific types) — this itself
 * demonstrates that the diff and layout layers depend only on the neutral model. Covers the
 * model-level dangling/null/reference distinction and a builder-only "second frontend" flow
 * through diff + layout.
 */
public class NeutralModelCutoverTest {

    private static DisplayableVariable var(String label, Value value) {
        return new DisplayableVariable(label, "T", value);
    }

    private static DisplayableThread thread(DisplayableFrame... frames) {
        return new DisplayableThread("th", "main", "suspended", List.of(frames), null);
    }

    private static DisplayableFrame frame(String id, List<DisplayableVariable> variables) {
        return new DisplayableFrame(id, id + "()", variables, null);
    }

    private static DisplayableStruct struct(String id, String type, List<DisplayableVariable> variables) {
        return new DisplayableStruct(id, type, variables, true, 0, null);
    }

    // ---------- a snapshot built purely via the builder diffs + lays out ----------
    @Test
    void testBuilderOnlySnapshotDiffsAndLaysOut() {
        MemorySnapshot v1 = MemorySnapshot.builder("t")
                .thread(thread(frame("f", List.of(var("x", new Value.BoxValue("1"))))))
                .fill(struct("P", "P #1", List.of(var("a", new Value.BoxValue("1")))))
                .build();
        MemorySnapshot v2 = MemorySnapshot.builder("t")
                .thread(thread(frame("f", List.of(var("x", new Value.BoxValue("2"))))))
                .fill(struct("P", "P #1", List.of(var("a", new Value.BoxValue("1")))))
                .build();

        MemoryDiff diff = DiffEngine.diff(v1, v2);
        assertEquals(ChangeStatus.UPDATED, diff.statusOf("f", "x"),
                "second-frontend snapshot: a changed local is diffed as UPDATED with no debugger");

        List<String> order = HeapLayouter.assign(v2, new LayoutMemory());
        assertEquals(List.of("P"), order, "second-frontend snapshot: the layouter orders its struct ids");
    }

    // ---------- dangling vs null vs live reference are distinct in the model ----------
    @Test
    void testDanglingNullAndLiveReferenceAreDistinct() {
        MemorySnapshot.Builder b = MemorySnapshot.builder("t");
        Value.Reference live = b.reference("R");
        Value.Reference dangling = b.reference("ghost"); // never provided
        b.thread(thread(frame("f", List.of(
                var("live", live),
                var("absent", new Value.BoxValue("null")),
                var("dangling", dangling)))));
        b.fill(struct("R", "R #1", List.of()));
        MemorySnapshot d = b.build();

        assertTrue(d.resolve(live).isPresent(), "a live reference resolves to its target struct");
        assertEquals(new Value.BoxValue("null"), d.threads().get(0).frames().get(0).variables().get(1).value(),
                "the debuggee's null is the box value \"null\" (not a reference)");
        assertTrue(d.resolve(dangling).isEmpty(),
                "a dangling reference resolves to nothing, distinct from a null cell and from a live reference");
    }

    // ---------- an unexplored (stub) struct never resolves as dangling ----------
    @Test
    void testStubTargetResolvesNotDangling() {
        MemorySnapshot.Builder b = MemorySnapshot.builder("t");
        Value.Reference ref = b.reference("big");
        b.thread(thread(frame("f", List.of(var("r", ref)))));
        b.reserve("big", "Big #7"); // over-cap: reserved as an unexplored stub, never filled
        MemorySnapshot d = b.build();

        DisplayableStruct target = d.resolve(ref).orElseThrow();
        assertFalse(target.explored(), "an over-cap target resolves to an unexplored stub, not a dangling pointer");
    }
}
