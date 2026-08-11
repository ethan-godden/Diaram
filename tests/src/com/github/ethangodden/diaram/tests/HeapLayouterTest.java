package com.github.ethangodden.diaram.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import com.github.ethangodden.diaram.model.MemorySnapshot;
import com.github.ethangodden.diaram.model.MemorySnapshot.DisplayableStruct;
import com.github.ethangodden.diaram.render.HeapLayouter;
import com.github.ethangodden.diaram.render.LayoutMemory;

/**
 * JUnit 5 tests for the id-keyed {@link HeapLayouter} + {@link LayoutMemory}.
 *
 * <p>The layouter trusts {@link MemorySnapshot#heap()}'s struct order and stabilizes it with a
 * {@link LayoutMemory} keyed on each struct's {@code id}: a struct remembered from an earlier
 * rebuild keeps its slot (sticky orderKey) even when the snapshot reorders the heap; a never-seen
 * struct appends after all remembered ones; ids absent from the latest snapshot are evicted.
 */
public class HeapLayouterTest {

    // ---------- builder helpers ----------

    /** A fresh snapshot builder (thread content is irrelevant to heap ordering). */
    private static MemorySnapshot.Builder builder() {
        return MemorySnapshot.builder("target");
    }

    /** Add a fully-explored, field-less struct with the given id as both id and type. */
    private static void box(MemorySnapshot.Builder b, String id) {
        b.fill(new DisplayableStruct(id, id, List.of(), true, 0, null));
    }

    // ---------- heap order ----------

    @Test
    void testOrderFollowsSnapshotStructOrder() {
        // Structs A, B, C added in that order => that is the column order.
        MemorySnapshot.Builder b = builder();
        box(b, "A");
        box(b, "B");
        box(b, "C");
        List<String> order = HeapLayouter.assign(b.build(), new LayoutMemory());
        assertEquals(List.of("A", "B", "C"), order,
                "order: heap column follows the snapshot's struct order");
    }

    @Test
    void testOrderDeterministicWithFreshMemory() {
        MemorySnapshot.Builder b1 = builder();
        box(b1, "A");
        box(b1, "B");
        box(b1, "C");
        List<String> order1 = HeapLayouter.assign(b1.build(), new LayoutMemory());

        MemorySnapshot.Builder b2 = builder();
        box(b2, "A");
        box(b2, "B");
        box(b2, "C");
        List<String> order2 = HeapLayouter.assign(b2.build(), new LayoutMemory());

        assertEquals(order1, order2, "order: deterministic across runs with fresh memory");
    }

    // ---------- sticky slot stability ----------

    @Test
    void testSlotStabilityAcrossReorderingRebuild() {
        LayoutMemory memory = new LayoutMemory();

        // step 1: snapshot order A, B.
        MemorySnapshot.Builder b1 = builder();
        box(b1, "A");
        box(b1, "B");
        List<String> order1 = HeapLayouter.assign(b1.build(), memory);
        assertEquals(List.of("A", "B"), order1, "stability: initial column order");
        Long keyA = memory.orderKeyOf("A");
        Long keyB = memory.orderKeyOf("B");

        // step 2: the snapshot reorders the heap to B, A. Remembered ids keep their earlier slots,
        // so the rendered column is unchanged (A before B) despite the snapshot's swap.
        MemorySnapshot.Builder b2 = builder();
        box(b2, "B");
        box(b2, "A");
        List<String> order2 = HeapLayouter.assign(b2.build(), memory);
        assertEquals(List.of("A", "B"), order2,
                "stability: remembered ids keep their slots despite snapshot reordering");
        assertEquals(keyA, memory.orderKeyOf("A"), "stability: A orderKey retained verbatim");
        assertEquals(keyB, memory.orderKeyOf("B"), "stability: B orderKey retained verbatim");
    }

    @Test
    void testNewStructAppendsAfterExisting() {
        LayoutMemory memory = new LayoutMemory();

        MemorySnapshot.Builder b1 = builder();
        box(b1, "A");
        box(b1, "B");
        HeapLayouter.assign(b1.build(), memory);

        // step 2: a NEW struct C appears (snapshot lists it first) — it must append after A and B.
        MemorySnapshot.Builder b2 = builder();
        box(b2, "C");
        box(b2, "A");
        box(b2, "B");
        List<String> order = HeapLayouter.assign(b2.build(), memory);
        assertEquals(List.of("A", "B", "C"), order,
                "append: a new struct sorts after every remembered struct");
        assertTrue(memory.orderKeyOf("C").longValue() > memory.orderKeyOf("B").longValue(),
                "append: new struct gets an orderKey after all existing ones");
    }

    // ---------- eviction ----------

    @Test
    void testEvictionDropsAbsentIds() {
        LayoutMemory memory = new LayoutMemory();

        MemorySnapshot.Builder b1 = builder();
        box(b1, "A");
        box(b1, "B");
        HeapLayouter.assign(b1.build(), memory);
        assertNotNull(memory.orderKeyOf("B"), "eviction: orderKey exists while live");

        // step 2: B is absent from the snapshot => evicted and off the column.
        MemorySnapshot.Builder b2 = builder();
        box(b2, "A");
        List<String> order = HeapLayouter.assign(b2.build(), memory);
        assertEquals(List.of("A"), order, "eviction: evicted id absent from the column");
        assertNull(memory.orderKeyOf("B"), "eviction: orderKey removed from memory");

        // step 3: if B reappears it is assigned fresh (appended), not restored to its old slot.
        MemorySnapshot.Builder b3 = builder();
        box(b3, "B");
        box(b3, "A");
        List<String> order3 = HeapLayouter.assign(b3.build(), memory);
        assertEquals(List.of("A", "B"), order3, "eviction: reappearing id re-assigned after existing");
        assertTrue(memory.orderKeyOf("B").longValue() > memory.orderKeyOf("A").longValue(),
                "eviction: reappearing id gets a fresh orderKey");
    }

    // ---------- LayoutMemory direct ----------

    @Test
    void testLayoutMemoryDirect() {
        LayoutMemory memory = new LayoutMemory();
        memory.assign("A");
        memory.assign("B");
        assertEquals(Long.valueOf(0), memory.orderKeyOf("A"), "memory: first assignment gets orderKey 0");
        assertEquals(Long.valueOf(1), memory.orderKeyOf("B"), "memory: second assignment increments orderKey");
        memory.assign("A");
        assertEquals(Long.valueOf(0), memory.orderKeyOf("A"), "memory: re-assign keeps orderKey verbatim");
        assertNull(memory.orderKeyOf("Z"), "memory: unknown id has no orderKey");

        memory.retainAll(Set.of("A"));
        assertNull(memory.orderKeyOf("B"), "memory: retainAll evicts absent ids");
        assertNotNull(memory.orderKeyOf("A"), "memory: retainAll keeps live ids");

        memory.clear();
        assertNull(memory.orderKeyOf("A"), "memory: clear drops orderKeys");
        memory.assign("C");
        assertEquals(Long.valueOf(0), memory.orderKeyOf("C"), "memory: clear resets the orderKey counter");
    }
}
