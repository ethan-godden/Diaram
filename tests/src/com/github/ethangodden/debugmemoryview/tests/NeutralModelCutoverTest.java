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
 * demonstrates that the diff and layout layers depend only on the neutral model. Covers ghost
 * references across snapshots, the model-level dangling/null/reference distinction, and a
 * builder-only "second frontend" flow through diff + layout.
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
        assertEquals(ChangeStatus.CHANGED, diff.variableStatusOf("f", "x"),
                "second-frontend snapshot: a changed local is diffed as CHANGED with no debugger");

        List<String> order = HeapLayouter.assign(v2, List.of(), new LayoutMemory());
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

    // ---------- ghost references stay live for surviving targets ----------
    @Test
    void testGhostReferenceResolvesToSurvivingTarget() {
        MemorySnapshot.Builder pb = MemorySnapshot.builder("t");
        Value.Reference toA = pb.reference("A");
        Value.Reference toB = pb.reference("B");
        pb.thread(thread(frame("f", List.of(var("a", toA)))));
        pb.fill(struct("A", "A #1", List.of(var("next", toB))));
        pb.fill(struct("B", "B #2", List.of()));
        MemorySnapshot prev = pb.build();

        // B survives, A is deleted; B now sits at a different heap position than in prev.
        MemorySnapshot curr = MemorySnapshot.builder("t")
                .thread(thread(frame("f", List.of())))
                .fill(struct("B", "B #2", List.of()))
                .build();

        MemoryDiff diff = DiffEngine.diff(prev, curr);
        DisplayableStruct ghostA = diff.deletedStructs().stream()
                .filter(x -> x.id().equals("A")).findFirst().orElseThrow();
        Value kept = ghostA.variables().get(0).value();
        assertTrue(kept instanceof Value.Reference, "ghost A's outgoing value is still a reference");
        assertEquals("B", curr.resolve((Value.Reference) kept).orElseThrow().id(),
                "ghost reference resolves to the surviving target in the CURRENT snapshot, not an unrelated struct");
    }

    @Test
    void testGhostReferenceToGoneTargetBecomesAbsent() {
        MemorySnapshot.Builder pb = MemorySnapshot.builder("t");
        Value.Reference toC = pb.reference("C");
        pb.thread(thread(frame("f", List.of())));
        pb.fill(struct("A", "A #1", List.of(var("next", toC))));
        pb.fill(struct("C", "C #2", List.of()));
        MemorySnapshot prev = pb.build();

        // Both A and its target C are gone; only an unrelated D remains.
        MemorySnapshot curr = MemorySnapshot.builder("t")
                .thread(thread(frame("f", List.of())))
                .fill(struct("D", "D #9", List.of()))
                .build();

        MemoryDiff diff = DiffEngine.diff(prev, curr);
        DisplayableStruct ghostA = diff.deletedStructs().stream()
                .filter(x -> x.id().equals("A")).findFirst().orElseThrow();
        assertEquals(new Value.BoxValue("null"), ghostA.variables().get(0).value(),
                "a ghost reference whose target is also gone becomes the box value \"null\" (no arrow), never a wrong-struct arrow");
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
