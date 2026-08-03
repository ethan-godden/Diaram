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
    void testInitialNullPrev() {
        Snap c = snap();
        c.frame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        c.struct("1", "P", List.of(var("P.a", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        c.struct("statics:app.Config", "Class Config", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("host", prim("h")))); //$NON-NLS-1$ //$NON-NLS-2$

        MemoryDiff d = DiffEngine.diff(null, c.build());
        assertEquals(ChangeStatus.NEW, d.frameStatusOf("f#main"), "initial: frame NEW"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ChangeStatus.NEW, d.variableStatusOf("f#main", "x"), "initial: variable NEW"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(ChangeStatus.NEW, d.structStatusOf("1"), "initial: heap struct NEW"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ChangeStatus.NEW, d.structStatusOf("statics:app.Config"), //$NON-NLS-1$
                "initial: statics struct NEW"); //$NON-NLS-1$
        assertTrue(d.deletedFrames().isEmpty() && d.deletedStructs().isEmpty()
                && d.deletedVariables().isEmpty(), "initial: no ghosts"); //$NON-NLS-1$
    }

    @Test
    void testThreadSwitch() {
        Snap p = new Snap("thread-A"); //$NON-NLS-1$
        p.frame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = new Snap("thread-B"); //$NON-NLS-1$
        c.frame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        // Falls back to an initial diff: same frame id on both sides still reads NEW, not UNCHANGED.
        assertEquals(ChangeStatus.NEW, d.frameStatusOf("f#main"), "thread switch: frame NEW"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ChangeStatus.NEW, d.variableStatusOf("f#main", "x"), //$NON-NLS-1$ //$NON-NLS-2$
                "thread switch: variable NEW"); //$NON-NLS-1$
    }

    @Test
    void testVariableChangeMarksVariableAndFrameChanged() {
        Snap p = snap();
        p.frame("f#run", "Demo.run() line 10", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("same", prim("1")), var("mut", prim("2")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.frame("f#run", "Demo.run() line 10", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("same", prim("1")), var("mut", prim("9")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UNCHANGED, d.variableStatusOf("f#run", "same"), //$NON-NLS-1$ //$NON-NLS-2$
                "var change: untouched variable UNCHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.CHANGED, d.variableStatusOf("f#run", "mut"), //$NON-NLS-1$ //$NON-NLS-2$
                "var change: mutated variable CHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.CHANGED, d.frameStatusOf("f#run"), //$NON-NLS-1$
                "var change: enclosing frame CHANGED"); //$NON-NLS-1$
    }

    @Test
    void testVanishedVariableIsDeletedAndFrameChanged() {
        Snap p = snap();
        p.frame("f#run", "Demo.run() line 10", //$NON-NLS-1$ //$NON-NLS-2$
                List.of(var("keep", prim("1")), var("gone", prim("3")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.frame("f#run", "Demo.run() line 10", List.of(var("keep", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        List<DisplayableVariable> ghosts = d.deletedVariables().get("f#run"); //$NON-NLS-1$
        assertTrue(ghosts != null && ghosts.size() == 1 && ghosts.get(0).label().equals("gone"), //$NON-NLS-1$
                "vanished var: recorded in deletedVariables under surviving frame id"); //$NON-NLS-1$
        assertEquals(ChangeStatus.CHANGED, d.frameStatusOf("f#run"), //$NON-NLS-1$
                "vanished var: enclosing frame CHANGED"); //$NON-NLS-1$
    }

    @Test
    void testPushedFrameNewSurvivorUnchanged() {
        Snap p = snap();
        p.frame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        // top-of-stack first: the pushed helper precedes main.
        c.frame("f#helper", "Demo.helper() line 20", List.of(var("h", prim("5")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        c.frame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.NEW, d.frameStatusOf("f#helper"), "push: pushed frame NEW"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ChangeStatus.NEW, d.variableStatusOf("f#helper", "h"), //$NON-NLS-1$ //$NON-NLS-2$
                "push: pushed frame's variable NEW"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UNCHANGED, d.frameStatusOf("f#main"), //$NON-NLS-1$
                "push: surviving frame UNCHANGED"); //$NON-NLS-1$
        assertTrue(d.deletedFrames().isEmpty(), "push: no deleted frames"); //$NON-NLS-1$
    }

    @Test
    void testPoppedFrameDeleted() {
        Snap p = snap();
        p.frame("f#helper", "Demo.helper() line 20", List.of(var("h", prim("5")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        p.frame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.frame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.DELETED, d.frameStatusOf("f#helper"), "pop: popped frame DELETED"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(d.deletedFrames().size() == 1
                && d.deletedFrames().get(0).id().equals("f#helper"), //$NON-NLS-1$
                "pop: popped frame model in deletedFrames"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UNCHANGED, d.frameStatusOf("f#main"), "pop: survivor UNCHANGED"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    void testLabelChangeOnSurvivingFrameChanged() {
        Snap p = snap();
        p.frame("f#main", "Demo.main() line 10", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.frame("f#main", "Demo.main() line 11", List.of(var("x", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.CHANGED, d.frameStatusOf("f#main"), //$NON-NLS-1$
                "label change: frame CHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UNCHANGED, d.variableStatusOf("f#main", "x"), //$NON-NLS-1$ //$NON-NLS-2$
                "label change: variable still UNCHANGED"); //$NON-NLS-1$
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
        assertEquals(ChangeStatus.CHANGED, d.structStatusOf("1"), //$NON-NLS-1$
                "struct field: field change => struct CHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UNCHANGED, d.fieldStatusOf("1", "x"), //$NON-NLS-1$ //$NON-NLS-2$
                "struct field: untouched field UNCHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.CHANGED, d.fieldStatusOf("1", "y"), //$NON-NLS-1$ //$NON-NLS-2$
                "struct field: mutated field CHANGED"); //$NON-NLS-1$
    }

    @Test
    void testShadowedFieldsPairByOccurrence() {
        // Two same-labeled rows (a shadowed field): rows pair by occurrence index, so only the
        // second occurrence ("x#2") reads as changed.
        Snap p = snap();
        p.struct("1", "Sub", List.of(var("x", prim("1")), var("x", prim("2")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        Snap c = snap();
        c.struct("1", "Sub", List.of(var("x", prim("1")), var("x", prim("9")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UNCHANGED, d.fieldStatusOf("1", "x"), //$NON-NLS-1$ //$NON-NLS-2$
                "shadowed: first occurrence keyed by bare label, UNCHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.CHANGED, d.fieldStatusOf("1", "x#2"), //$NON-NLS-1$ //$NON-NLS-2$
                "shadowed: second occurrence keyed label#2, CHANGED"); //$NON-NLS-1$
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
        assertEquals(ChangeStatus.CHANGED, d.structStatusOf("10"), //$NON-NLS-1$
                "array: element change => struct CHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.CHANGED, d.fieldStatusOf("10", "1"), //$NON-NLS-1$ //$NON-NLS-2$
                "array: changed element's row CHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UNCHANGED, d.fieldStatusOf("10", "0"), //$NON-NLS-1$ //$NON-NLS-2$
                "array: untouched element UNCHANGED"); //$NON-NLS-1$
    }

    @Test
    void testReferenceRetargetedIsRowChange() {
        // prev: r -> struct A ; curr: r -> struct B. A target change on the referring row.
        Snap p = snap();
        p.reserve("A", "A"); //$NON-NLS-1$ //$NON-NLS-2$
        p.frame("f#run", "Demo.run() line 5", List.of(var("r", p.ref("A")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap();
        c.reserve("A", "A"); //$NON-NLS-1$ //$NON-NLS-2$
        c.reserve("B", "B"); //$NON-NLS-1$ //$NON-NLS-2$
        c.frame("f#run", "Demo.run() line 5", List.of(var("r", c.ref("B")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.CHANGED, d.variableStatusOf("f#run", "r"), //$NON-NLS-1$ //$NON-NLS-2$
                "retarget: referring row CHANGED"); //$NON-NLS-1$
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
        assertEquals(ChangeStatus.UNCHANGED, d.variableStatusOf("f#run", "r"), //$NON-NLS-1$ //$NON-NLS-2$
                "same target: referring row UNCHANGED"); //$NON-NLS-1$
    }

    @Test
    void testUnreadablePrimitivesCompareEqual() {
        // The unreadable mapping: UnreadableValue -> Primitive("?"). Two of them are EQUAL.
        Snap p = snap();
        p.frame("f#run", "Demo.run() line 5", List.of(var("u", prim(UNREADABLE)))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Snap c = snap();
        c.frame("f#run", "Demo.run() line 5", List.of(var("u", prim(UNREADABLE)))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UNCHANGED, d.variableStatusOf("f#run", "u"), //$NON-NLS-1$ //$NON-NLS-2$
                "unreadable: two Primitive(\"?\") compare EQUAL (no spurious change)"); //$NON-NLS-1$
    }

    @Test
    void testUnexploredStructNeverChanged() {
        // A reserved (stub, explored=false) struct is never claimed as changed, even opposite an
        // explored struct with content.
        Snap p = snap();
        p.reserve("4", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        Snap c = snap();
        c.struct("4", "P", List.of(var("a", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MemoryDiff d1 = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.UNCHANGED, d1.structStatusOf("4"), //$NON-NLS-1$
                "unexplored: stub->explored UNCHANGED"); //$NON-NLS-1$

        Snap p2 = snap();
        p2.struct("4", "P", List.of(var("a", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c2 = snap();
        c2.reserve("4", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        MemoryDiff d2 = DiffEngine.diff(p2.build(), c2.build());
        assertEquals(ChangeStatus.UNCHANGED, d2.structStatusOf("4"), //$NON-NLS-1$
                "unexplored: explored->stub UNCHANGED"); //$NON-NLS-1$
    }

    @Test
    void testDeletedStructGhostedOnce() {
        Snap p = snap();
        p.struct("3", "P", List.of(var("a", prim("1")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Snap c = snap(); // heap now empty

        MemoryDiff d = DiffEngine.diff(p.build(), c.build());
        assertEquals(ChangeStatus.DELETED, d.structStatusOf("3"), "deleted struct: DELETED status"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(d.deletedStructs().size() == 1 && d.deletedStructs().get(0).id().equals("3"), //$NON-NLS-1$
                "deleted struct: present in deletedStructs exactly once"); //$NON-NLS-1$
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
        assertEquals(ChangeStatus.CHANGED, d.structStatusOf("statics:app.Config"), //$NON-NLS-1$
                "statics: field change => struct CHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.CHANGED, d.fieldStatusOf("statics:app.Config", "port"), //$NON-NLS-1$ //$NON-NLS-2$
                "statics: mutated field CHANGED"); //$NON-NLS-1$
        assertEquals(ChangeStatus.UNCHANGED, d.fieldStatusOf("statics:app.Config", "host"), //$NON-NLS-1$ //$NON-NLS-2$
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
        assertTrue(d.frameStatusOf("f#main") == ChangeStatus.UNCHANGED //$NON-NLS-1$
                && d.structStatusOf("1") == ChangeStatus.UNCHANGED //$NON-NLS-1$
                && d.deletedFrames().isEmpty() && d.deletedStructs().isEmpty()
                && d.deletedVariables().isEmpty(),
                "identical: nothing changed and no ghosts"); //$NON-NLS-1$
    }
}
