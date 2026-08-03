package com.github.ethangodden.debugmemoryview.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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

/** JUnit 5 tests for {@link DiffEngine} / {@link MemoryDiff} over the neutral model. */
public class DiffEngineTest {

    // ---------- factory helpers ----------

    private static final String UNREADABLE = "?"; //$NON-NLS-1$

    /** A primitive display-string value. */
    private static Value.BoxValue prim(String text) {
        return new Value.BoxValue(text);
    }

    /** A variable row whose label doubles as its diff identity (row key). */
    private static DisplayableVariable var(String label, Value value) {
        return new DisplayableVariable(label, "int", value); //$NON-NLS-1$
    }

    /**
     * Accumulates frames and heap structs, then builds a single-thread snapshot — the shape the
     * extractor produces (one suspended thread sharing the heap).
     */
    private static final class Snap {
        final MemorySnapshot.Builder b = MemorySnapshot.builder("target"); //$NON-NLS-1$
        final List<DisplayableFrame> frames = new ArrayList<>();
        final String threadId;

        Snap(String threadId) {
            this.threadId = threadId;
        }

        void frame(String id, String label, List<DisplayableVariable> variables) {
            frames.add(new DisplayableFrame(id, label, variables, null));
        }

        void struct(String id, String type, List<DisplayableVariable> variables) {
            b.fill(new DisplayableStruct(id, type, variables, true, 0, null));
        }

        void reserve(String id, String type) {
            b.reserve(id, type);
        }

        Value.Reference ref(String id) {
            return b.reference(id);
        }

        MemorySnapshot build() {
            b.thread(new DisplayableThread(threadId, "main", "suspended", frames, null)); //$NON-NLS-1$ //$NON-NLS-2$
            return b.build();
        }
    }

    /** A fresh accumulator for the default thread ("thread-1"). */
    private static Snap snap() {
        return new Snap("thread-1"); //$NON-NLS-1$
    }

    // ---------- tests ----------

    @Test
    void testInitialNullPrevEveryRowNew() {
        Snap c = snap();
        c.frame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        c.struct("1", "P", List.of(var("P.a", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        c.struct("statics:app.Config", "Class Config", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("host", prim("h")))); //$NON-NLS-1$ //$NON-NLS-2$

        MemoryDiff d = DiffEngine.diff(null, c.build());
        assertEquals(ChangeStatus.NEW, d.statusOf("f#main", "x"), "initial: frame local NEW"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(ChangeStatus.NEW, d.statusOf("1", "P.a"), "initial: struct field NEW"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(ChangeStatus.NEW, d.statusOf("statics:app.Config", "host"), //$NON-NLS-1$ //$NON-NLS-2$
                "initial: statics field NEW"); //$NON-NLS-1$
    }

    @Test
    void testThreadSwitchEveryRowNew() {
        Snap p = new Snap("thread-A"); //$NON-NLS-1$
        p.frame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        p.struct("1", "P", List.of(var("a", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = new Snap("thread-B"); //$NON-NLS-1$
        c.frame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        c.struct("1", "P", List.of(var("a", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        // Every address resets: the same row key on both sides still reads NEW, not UNCHANGED.
        assertEquals(ChangeStatus.NEW, d.statusOf("f#main", "x"), "thread switch: frame local NEW"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(ChangeStatus.NEW, d.statusOf("1", "a"), //$NON-NLS-1$ //$NON-NLS-2$
                "thread switch: persisting struct's row also NEW"); //$NON-NLS-1$
    }

    @Test
    void testLocalValueChangeUpdated() {
        Snap p = snap();
        p.frame("f#run", "Demo.run() line 10", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("same", prim("1")), var("mut", prim("2")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.frame("f#run", "Demo.run() line 10", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("same", prim("1")), var("mut", prim("9")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UNCHANGED, d.statusOf("f#run", "same"), //$NON-NLS-1$ //$NON-NLS-2$
                "value change: untouched variable UNCHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UPDATED, d.statusOf("f#run", "mut"), //$NON-NLS-1$ //$NON-NLS-2$
                "value change: mutated variable UPDATED"); //$NON-NLS-1$
    }

    @Test
    void testSameLabelInDifferentFrameIsNew() {
        // The same identifier at a different address (another frame id) is a different variable.
        Snap p = snap();
        p.frame("f#1:main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.frame("f#2:helper", "Demo.helper() line 20", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        c.frame("f#1:main", "Demo.main() line 12", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.NEW, d.statusOf("f#2:helper", "x"), //$NON-NLS-1$ //$NON-NLS-2$
                "different frame: same-labeled local NEW"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UNCHANGED, d.statusOf("f#1:main", "x"), //$NON-NLS-1$ //$NON-NLS-2$
                "surviving frame: same local UNCHANGED"); //$NON-NLS-1$
    }

    @Test
    void testVanishedVariableNotTracked() {
        Snap p = snap();
        p.frame("f#run", "Demo.run() line 10", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("keep", prim("1")), var("gone", prim("3")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.frame("f#run", "Demo.run() line 10", List.of(var("keep", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertTrue(d.rows().isEmpty(), "vanished var: removals leave no record"); //$NON-NLS-1$
    }

    @Test
    void testPoppedFrameNotTracked() {
        Snap p = snap();
        p.frame("f#helper", "Demo.helper() line 20", List.of(var("h", prim("5")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        p.frame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.frame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertTrue(d.rows().isEmpty(), "pop: popped frame's rows leave no record"); //$NON-NLS-1$
    }

    @Test
    void testFrameLabelChangeIsNotAVariableChange() {
        // A step keeps the frame id; only the label (line number) moves. No variable changed.
        Snap p = snap();
        p.frame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.frame("f#main", "Demo.main() line 11", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertTrue(d.rows().isEmpty(), "label change: no variable change recorded"); //$NON-NLS-1$
    }

    @Test
    void testStructFieldChange() {
        Snap p = snap();
        p.struct("1", "Point", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("x", prim("1")), var("y", prim("2")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.struct("1", "Point", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("x", prim("1")), var("y", prim("3")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UNCHANGED, d.statusOf("1", "x"), //$NON-NLS-1$ //$NON-NLS-2$
                "struct field: untouched field UNCHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UPDATED, d.statusOf("1", "y"), //$NON-NLS-1$ //$NON-NLS-2$
                "struct field: mutated field UPDATED"); //$NON-NLS-1$
    }

    @Test
    void testNewStructRowsNew() {
        Snap p = snap();
        Snap c = snap();
        c.struct("7", "Point", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.NEW, d.statusOf("7", "x"), //$NON-NLS-1$ //$NON-NLS-2$
                "new struct: its rows are NEW variables"); //$NON-NLS-1$
    }

    @Test
    void testShadowedFieldsPairByOccurrence() {
        // Two same-labeled rows (a shadowed field): rows pair by occurrence index, so only the
        // second occurrence ("x#2") reads as updated.
        Snap p = snap();
        p.struct("1", "Sub", List.of(var("x", prim("1")), var("x", prim("2")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        Snap c = snap();
        c.struct("1", "Sub", List.of(var("x", prim("1")), var("x", prim("9")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UNCHANGED, d.statusOf("1", "x"), //$NON-NLS-1$ //$NON-NLS-2$
                "shadowed: first occurrence keyed by bare label, UNCHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UPDATED, d.statusOf("1", "x#2"), //$NON-NLS-1$ //$NON-NLS-2$
                "shadowed: second occurrence keyed label#2, UPDATED"); //$NON-NLS-1$
    }

    @Test
    void testArrayElementChange() {
        // Arrays are structs whose rows use positional labels "0","1",...
        Snap p = snap();
        p.struct("10", "int[3]", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("0", prim("1")), var("1", prim("2")), var("2", prim("3")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        Snap c = snap();
        c.struct("10", "int[3]", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("0", prim("1")), var("1", prim("9")), var("2", prim("3")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UPDATED, d.statusOf("10", "1"), //$NON-NLS-1$ //$NON-NLS-2$
                "array: changed element's row UPDATED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UNCHANGED, d.statusOf("10", "0"), //$NON-NLS-1$ //$NON-NLS-2$
                "array: untouched element UNCHANGED"); //$NON-NLS-1$
    }

    @Test
    void testReferenceRetargetedIsRowUpdate() {
        // prev: r -> struct A ; curr: r -> struct B. A target change on the referring row.
        Snap p = snap();
        p.reserve("A", "A"); //$NON-NLS-1$ //$NON-NLS-2$
        p.frame("f#run", "Demo.run() line 5", List.of(var("r", p.ref("A")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.reserve("A", "A"); //$NON-NLS-1$ //$NON-NLS-2$
        c.reserve("B", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        c.frame("f#run", "Demo.run() line 5", List.of(var("r", c.ref("B")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UPDATED, d.statusOf("f#run", "r"), //$NON-NLS-1$ //$NON-NLS-2$
                "retarget: referring row UPDATED"); //$NON-NLS-1$
    }

    @Test
    void testReferenceSameTargetUnchanged() {
        Snap p = snap();
        p.reserve("A", "A"); //$NON-NLS-1$ //$NON-NLS-2$
        p.frame("f#run", "Demo.run() line 5", List.of(var("r", p.ref("A")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.reserve("A", "A"); //$NON-NLS-1$ //$NON-NLS-2$
        c.frame("f#run", "Demo.run() line 5", List.of(var("r", c.ref("A")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UNCHANGED, d.statusOf("f#run", "r"), //$NON-NLS-1$ //$NON-NLS-2$
                "same target: referring row UNCHANGED"); //$NON-NLS-1$
    }

    @Test
    void testStructFieldReferenceRetargetIsUpdate() {
        // The canonical linked-list mutation: node.next moves from A to B. The struct row path
        // (behind the unexplored guard) must report it just like a frame local would.
        Snap p = snap();
        p.reserve("A", "A"); //$NON-NLS-1$ //$NON-NLS-2$
        p.struct("node", "Node", List.of(var("next", p.ref("A")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Snap c = snap();
        c.reserve("A", "A"); //$NON-NLS-1$ //$NON-NLS-2$
        c.reserve("B", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        c.struct("node", "Node", List.of(var("next", c.ref("B")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UPDATED, d.statusOf("node", "next"), //$NON-NLS-1$ //$NON-NLS-2$
                "struct retarget: referring field UPDATED"); //$NON-NLS-1$
    }

    @Test
    void testDanglingReferencesCompareEqual() {
        // Two dangling references are equal, even with different target tokens.
        Snap p = snap();
        p.frame("f#run", "Demo.run() line 5", List.of(var("r", p.ref("goneX")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.frame("f#run", "Demo.run() line 5", List.of(var("r", c.ref("goneY")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UNCHANGED, d.statusOf("f#run", "r"), //$NON-NLS-1$ //$NON-NLS-2$
                "dangling both sides: referring row UNCHANGED"); //$NON-NLS-1$
    }

    @Test
    void testLiveToDanglingReferenceIsUpdate() {
        Snap p = snap();
        p.reserve("A", "A"); //$NON-NLS-1$ //$NON-NLS-2$
        p.frame("f#run", "Demo.run() line 5", List.of(var("r", p.ref("A")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.frame("f#run", "Demo.run() line 5", List.of(var("r", c.ref("A")))); // A never provided in curr //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UPDATED, d.statusOf("f#run", "r"), //$NON-NLS-1$ //$NON-NLS-2$
                "live -> dangling: referring row UPDATED"); //$NON-NLS-1$
    }

    @Test
    void testNullToReferenceIsUpdate() {
        // Different kinds of Value are never equal: x == null, then x = new P().
        Snap p = snap();
        p.frame("f#run", "Demo.run() line 5", List.of(var("x", prim("null")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.reserve("A", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        c.frame("f#run", "Demo.run() line 5", List.of(var("x", c.ref("A")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UPDATED, d.statusOf("f#run", "x"), //$NON-NLS-1$ //$NON-NLS-2$
                "null -> reference: row UPDATED"); //$NON-NLS-1$
    }

    @Test
    void testUnreadablePrimitivesCompareEqual() {
        // The unreadable mapping: UnreadableValue -> BoxValue("?"). Two of them are EQUAL.
        Snap p = snap();
        p.frame("f#run", "Demo.run() line 5", List.of(var("u", prim(UNREADABLE)))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Snap c = snap();
        c.frame("f#run", "Demo.run() line 5", List.of(var("u", prim(UNREADABLE)))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UNCHANGED, d.statusOf("f#run", "u"), //$NON-NLS-1$ //$NON-NLS-2$
                "unreadable: two BoxValue(\"?\") compare EQUAL (no spurious change)"); //$NON-NLS-1$
    }

    @Test
    void testUnexploredStructRowsNeverClaimed() {
        // A reserved (stub, explored=false) struct has unknown contents: rows revealed by exploring
        // an already-known struct are never claimed NEW or UPDATED.
        Snap p = snap();
        p.reserve("4", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        Snap c = snap();
        c.struct("4", "P", List.of(var("a", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d1 = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UNCHANGED, d1.statusOf("4", "a"), //$NON-NLS-1$ //$NON-NLS-2$
                "unexplored: stub->explored rows UNCHANGED"); //$NON-NLS-1$

        Snap p2 = snap();
        p2.struct("4", "P", List.of(var("a", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c2 = snap();
        c2.reserve("4", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        MemoryDiff d2 = DiffEngine.diff(p2.build(), c2.build());
        assertTrue(d2.rows().isEmpty(), "unexplored: explored->stub leaves no record"); //$NON-NLS-1$
    }

    @Test
    void testDeletedStructNotTracked() {
        Snap p = snap();
        p.struct("3", "P", List.of(var("a", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap(); // heap now empty

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertTrue(d.rows().isEmpty(), "deleted struct: removals leave no record"); //$NON-NLS-1$
    }

    @Test
    void testStaticsStructBehavesLikeAnyStruct() {
        // A statics-class struct (id "statics:<class>") diffs by row like a regular struct.
        Snap p = snap();
        p.struct("statics:app.Config", "Class Config", List.of( //$NON-NLS-1$ //$NON-NLS-2$
                var("host", prim("h")), //$NON-NLS-1$ //$NON-NLS-2$
                var("port", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$
        Snap c = snap();
        c.struct("statics:app.Config", "Class Config", List.of( //$NON-NLS-1$ //$NON-NLS-2$
                var("host", prim("h")), //$NON-NLS-1$ //$NON-NLS-2$
                var("port", prim("2")))); //$NON-NLS-1$ //$NON-NLS-2$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UPDATED, d.statusOf("statics:app.Config", "port"), //$NON-NLS-1$ //$NON-NLS-2$
                "statics: mutated field UPDATED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UNCHANGED, d.statusOf("statics:app.Config", "host"), //$NON-NLS-1$ //$NON-NLS-2$
                "statics: untouched field UNCHANGED"); //$NON-NLS-1$
    }

    @Test
    void testIdenticalSnapshotUnchanged() {
        Snap p = snap();
        p.frame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        p.struct("1", "P", List.of(var("a", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.frame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        c.struct("1", "P", List.of(var("a", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertTrue(d.rows().isEmpty(), "identical: nothing recorded"); //$NON-NLS-1$
    }
}
