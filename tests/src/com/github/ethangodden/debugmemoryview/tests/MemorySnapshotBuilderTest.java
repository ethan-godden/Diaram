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

/** JUnit 5 tests for {@link MemorySnapshot.Builder} — JDK-only, no debugger or editor. */
public class MemorySnapshotBuilderTest {

    // ---------- model builders ----------
    private static MemorySnapshot.Builder builder() {
        return MemorySnapshot.builder("target");
    }

    private static DisplayableVariable var(String label, Value value) {
        return new DisplayableVariable(label, "int", value);
    }

    private static DisplayableFrame frame(String id, String label, List<DisplayableVariable> variables) {
        return new DisplayableFrame(id, label, variables, null);
    }

    private static DisplayableThread thread(DisplayableFrame... frames) {
        return new DisplayableThread("thread-1", "main", "suspended", List.of(frames), null);
    }

    private static DisplayableStruct struct(String id, String type, List<DisplayableVariable> variables) {
        return new DisplayableStruct(id, type, variables, true, 0, null);
    }

    // ---------- threads & frames ----------
    @Test
    void testFrameKeepsVariableOrderThisFirst() {
        MemorySnapshot d = builder().thread(thread(
                frame("f0", "Demo.main() line 3",
                        List.of(var("this", new Value.BoxValue("Demo")), var("x", new Value.BoxValue("1"))))))
                .build();
        DisplayableFrame f = d.threads().get(0).frames().get(0);
        assertEquals(2, f.variables().size(), "frame keeps both variable rows");
        assertEquals("this", f.variables().get(0).label(), "this row comes first, in supplied order");
    }

    @Test
    void testFramesTopOfStackFirst() {
        MemorySnapshot d = builder().thread(thread(
                frame("top", "Demo.inner() line 9", List.of()),
                frame("bottom", "Demo.main() line 3", List.of())))
                .build();
        assertEquals("top", d.threads().get(0).frames().get(0).id(),
                "frames render in supplied order (top-of-stack first)");
    }

    @Test
    void testNoteOnlyFrame() {
        MemorySnapshot d = builder().thread(new DisplayableThread("thread-1", "main", "suspended",
                List.of(new DisplayableFrame("native0", "Thread.sleep()", List.of(), "(native method)")), null))
                .build();
        DisplayableFrame f = d.threads().get(0).frames().get(0);
        assertEquals("(native method)", f.note(), "a note-only frame preserves its note text");
    }

    // ---------- structs ----------
    @Test
    void testFillKeepsVariablesAndAppearsInHeap() {
        MemorySnapshot d = builder()
                .fill(struct("p1", "P #1", List.of(var("a", new Value.BoxValue("7")))))
                .build();
        DisplayableStruct s = d.heap().get(0);
        assertEquals("p1", s.id(), "the filled struct is in the heap");
        assertEquals("a", s.variables().get(0).label(), "struct keeps its variable rows");
    }

    @Test
    void testHeapOrderIsDiscoveryOrder() {
        MemorySnapshot d = builder()
                .fill(struct("a", "A", List.of()))
                .fill(struct("b", "B", List.of()))
                .build();
        assertEquals("a", d.heap().get(0).id(), "heap structs keep discovery order: a before b");
    }

    @Test
    void testReserveClaimsDiscoveryOrderBeforeFill() {
        // The extractor reserves statics first so they stay at the top of the heap column even
        // though later fills arrive in BFS order; fill must replace the stub in place.
        MemorySnapshot.Builder b = builder();
        b.reserve("early", "E");
        b.fill(struct("late", "L", List.of()));
        b.fill(struct("early", "E", List.of()));
        MemorySnapshot d = b.build();
        assertEquals("early", d.heap().get(0).id(), "a reserved struct keeps its discovery slot across fill");
        assertTrue(d.heap().get(0).explored(), "fill replaces the unexplored stub in place");
    }

    @Test
    void testOmittedAndExploredCarry() {
        MemorySnapshot d = builder()
                .fill(new DisplayableStruct("arr", "int[5] #2",
                        List.of(var("0", new Value.BoxValue("9"))), true, 4, null))
                .build();
        assertEquals(4, d.heap().get(0).omitted(), "omitted (capped elements) is carried on the struct");
    }

    // ---------- references, stubs, dangling, forward ----------
    @Test
    void testReferenceResolvesToTargetStruct() {
        MemorySnapshot.Builder b = builder();
        Value.Reference ref = b.reference("t");
        b.fill(struct("t", "T #5", List.of()));
        MemorySnapshot d = b.build();
        assertEquals("t", d.resolve(ref).orElseThrow().id(), "a reference resolves to its target struct");
    }

    @Test
    void testForwardReferenceResolvesAfterFill() {
        MemorySnapshot.Builder b = builder();
        // Mint the reference BEFORE the target struct is filled.
        Value.Reference ref = b.reference("late");
        b.thread(thread(frame("f0", "Demo.main()", List.of(var("r", ref)))));
        b.fill(struct("late", "Late #8", List.of()));
        MemorySnapshot d = b.build();
        assertTrue(d.resolve(ref).isPresent(), "a forward reference resolves to the struct filled after it");
    }

    @Test
    void testReserveIsUnexploredStubThatStillResolves() {
        MemorySnapshot.Builder b = builder();
        Value.Reference ref = b.reference("stub");
        b.reserve("stub", "Big #9");
        MemorySnapshot d = b.build();
        DisplayableStruct stub = d.resolve(ref).orElseThrow();
        assertFalse(stub.explored(), "a reserved-but-unfilled struct is an unexplored stub");
    }

    @Test
    void testDanglingReferenceResolvesToNothing() {
        MemorySnapshot.Builder b = builder();
        Value.Reference ref = b.reference("ghost"); // never reserved or filled
        MemorySnapshot d = b.build();
        assertTrue(d.resolve(ref).isEmpty(), "a reference to a never-provided id is dangling (resolves to nothing)");
    }

    @Test
    void testReferenceResolvesAcrossSnapshotsOfSameTarget() {
        // Ghost arrows depend on this: a reference minted for snapshot 1 must resolve against
        // snapshot 2 of the SAME target when the struct still exists there.
        Value.Reference ref = MemorySnapshot.builder("target").reference("X");
        MemorySnapshot next = MemorySnapshot.builder("target").fill(struct("X", "X #1", List.of())).build();
        assertEquals("X", next.resolve(ref).orElseThrow().id(),
                "a same-target reference resolves against a later snapshot");
    }

    @Test
    void testReferenceIsScopedToItsTarget() {
        // The targetId is baked into the token: another target's snapshot is a clean miss.
        Value.Reference foreign = MemorySnapshot.builder("other-target").reference("X");
        MemorySnapshot d = MemorySnapshot.builder("target").fill(struct("X", "X #1", List.of())).build();
        assertTrue(d.resolve(foreign).isEmpty(),
                "a reference minted for another target dangles instead of hitting an id collision");
    }

    // ---------- values ----------
    @Test
    void testNullValueIsNullText() {
        MemorySnapshot d = builder()
                .thread(thread(frame("f0", "Demo.main()", List.of(var("n", new Value.BoxValue("null"))))))
                .build();
        DisplayableVariable v = d.threads().get(0).frames().get(0).variables().get(0);
        assertEquals(new Value.BoxValue("null"), v.value(), "the debuggee's null is the box value \"null\"");
    }
}
