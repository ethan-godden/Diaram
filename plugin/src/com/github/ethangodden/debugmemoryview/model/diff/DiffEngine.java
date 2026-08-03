package com.github.ethangodden.debugmemoryview.model.diff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

import org.eclipse.jdt.annotation.Nullable;

import com.github.ethangodden.debugmemoryview.model.MemorySnapshot;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableFrame;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableStruct;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableThread;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableVariable;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.Value;

/**
 * Computes a {@link MemoryDiff} between two consecutive {@link MemorySnapshot}s on the same thread.
 * Identity is by opaque token: frames by frame id, variables by row key within a frame, structs by
 * struct id, fields by row key within a struct. A struct that is unexplored on either side is never
 * claimed as changed. References compare by RESOLVED TARGET — retargeting is the change on the
 * referring row; a target's own mutation shows on the target struct. Two dangling references compare
 * equal; two unreadable values (both {@code Primitive("?")}) compare equal.
 */
public final class DiffEngine {

    private DiffEngine() {
    }

    public static MemoryDiff diff(@Nullable MemorySnapshot prev, MemorySnapshot curr) {
        if (prev == null || !threadIds(prev).equals(threadIds(curr))) {
            return MemoryDiff.initial(curr);
        }
        Map<String, ChangeStatus> frameStatus = new HashMap<>();
        Map<String, ChangeStatus> variableStatus = new HashMap<>();
        List<DisplayableFrame> deletedFrames = new ArrayList<>();
        Map<String, List<DisplayableVariable>> deletedVariables = new HashMap<>();
        diffFrames(prev, curr, frameStatus, variableStatus, deletedFrames, deletedVariables);

        Map<String, ChangeStatus> structStatus = new HashMap<>();
        Map<String, Map<String, ChangeStatus>> fieldStatus = new HashMap<>();
        List<DisplayableStruct> deletedStructs = new ArrayList<>();
        diffHeap(prev, curr, structStatus, fieldStatus, deletedStructs);

        // Ghosts are copied verbatim from the PREVIOUS snapshot. Reference tokens are stable across
        // snapshots of one target, so a ghost's live reference already resolves against the CURRENT
        // snapshot with no coordinate remap; only a reference whose target is gone (or that was
        // already dangling) is rewritten to the absent value, so a deleted item's row renders an
        // empty cell (no arrow) rather than a wrong-struct arrow or a dangling glyph.
        List<DisplayableFrame> ghostFrames = new ArrayList<>(deletedFrames.size());
        for (DisplayableFrame f : deletedFrames) {
            ghostFrames.add(ghostFrame(f, prev, curr));
        }
        Map<String, List<DisplayableVariable>> ghostVars = new HashMap<>();
        for (Map.Entry<String, List<DisplayableVariable>> e : deletedVariables.entrySet()) {
            ghostVars.put(e.getKey(), ghostVariables(e.getValue(), prev, curr));
        }
        List<DisplayableStruct> ghostStructs = new ArrayList<>(deletedStructs.size());
        for (DisplayableStruct s : deletedStructs) {
            ghostStructs.add(ghostStruct(s, prev, curr));
        }

        return new MemoryDiff(Map.copyOf(frameStatus), Map.copyOf(variableStatus),
                Map.copyOf(structStatus), Map.copyOf(fieldStatus), List.copyOf(ghostFrames),
                Map.copyOf(ghostVars), List.copyOf(ghostStructs));
    }

    private static List<String> threadIds(MemorySnapshot s) {
        return s.threads().stream().map(DisplayableThread::id).toList();
    }

    /** All frames of a snapshot, across threads, in thread then stack order (top-of-stack first). */
    private static List<DisplayableFrame> allFrames(MemorySnapshot s) {
        return s.threads().stream().flatMap(t -> t.frames().stream()).toList();
    }

    /** A ghost reference survives only if it resolved in prev AND its target still exists in curr;
     * otherwise it becomes {@code new Value.BoxValue("null")} (a "null" cell, no arrow).
     * Non-references pass through. */
    private static Value ghostValue(Value v, MemorySnapshot prev, MemorySnapshot curr) {
        if (!(v instanceof Value.Reference ref)) {
            return v;
        }
        if (prev.resolve(ref).isEmpty() || curr.resolve(ref).isEmpty()) {
            return new Value.BoxValue("null");
        }
        return ref;
    }

    private static List<DisplayableVariable> ghostVariables(List<DisplayableVariable> vars,
            MemorySnapshot prev, MemorySnapshot curr) {
        List<DisplayableVariable> out = new ArrayList<>(vars.size());
        for (DisplayableVariable v : vars) {
            out.add(new DisplayableVariable(v.label(), v.type(), ghostValue(v.value(), prev, curr)));
        }
        return out;
    }

    private static DisplayableStruct ghostStruct(DisplayableStruct s, MemorySnapshot prev, MemorySnapshot curr) {
        return new DisplayableStruct(s.id(), s.type(), ghostVariables(s.variables(), prev, curr),
                s.explored(), s.omitted(), s.monitor());
    }

    private static DisplayableFrame ghostFrame(DisplayableFrame f, MemorySnapshot prev, MemorySnapshot curr) {
        if (f.note() != null) {
            return f;
        }
        return new DisplayableFrame(f.id(), f.label(), ghostVariables(f.variables(), prev, curr), null);
    }

    /** The old side's rows indexed by row key, insertion-ordered so leftovers keep their row order. */
    private static Map<String, DisplayableVariable> byRowKey(List<DisplayableVariable> rows) {
        Map<String, DisplayableVariable> byKey = new LinkedHashMap<>();
        List<String> keys = MemoryDiff.rowKeys(rows);
        for (int i = 0; i < keys.size(); i++) {
            byKey.put(keys.get(i), rows.get(i));
        }
        return byKey;
    }

    /**
     * Diffs {@code currRows} against {@code oldByKey} (consuming matches), reporting each current
     * row's (rowKey, status) to {@code sink}. Returns true when any row is NEW or CHANGED; the
     * entries left in {@code oldByKey} afterwards are the vanished rows.
     */
    private static boolean diffRows(List<DisplayableVariable> currRows,
            Map<String, DisplayableVariable> oldByKey, MemorySnapshot curr, MemorySnapshot prev,
            BiConsumer<String, ChangeStatus> sink) {
        boolean changed = false;
        List<String> keys = MemoryDiff.rowKeys(currRows);
        for (int i = 0; i < keys.size(); i++) {
            DisplayableVariable oldRow = oldByKey.remove(keys.get(i));
            ChangeStatus status = oldRow == null ? ChangeStatus.NEW
                    : valueEquals(currRows.get(i).value(), oldRow.value(), curr, prev) ? ChangeStatus.UNCHANGED
                            : ChangeStatus.CHANGED;
            if (status != ChangeStatus.UNCHANGED) {
                changed = true;
            }
            sink.accept(keys.get(i), status);
        }
        return changed;
    }

    private static void diffFrames(MemorySnapshot prev, MemorySnapshot curr,
            Map<String, ChangeStatus> frameStatus, Map<String, ChangeStatus> variableStatus,
            List<DisplayableFrame> deletedFrames, Map<String, List<DisplayableVariable>> deletedVariables) {

        Map<String, DisplayableFrame> prevById = new LinkedHashMap<>();
        for (DisplayableFrame f : allFrames(prev)) {
            prevById.put(f.id(), f);
        }

        for (DisplayableFrame f : allFrames(curr)) {
            DisplayableFrame old = prevById.remove(f.id());
            if (old == null) {
                frameStatus.put(f.id(), ChangeStatus.NEW);
                for (String key : MemoryDiff.rowKeys(f.variables())) {
                    variableStatus.put(MemoryDiff.variableKey(f.id(), key), ChangeStatus.NEW);
                }
                continue;
            }
            Map<String, DisplayableVariable> oldVars = byRowKey(old.variables());
            // The label carries the line number and the note carries any body string, so a step
            // within the same frame (same id) reads as a label change here.
            boolean changed = !Objects.equals(f.label(), old.label()) || !Objects.equals(f.note(), old.note());
            changed |= diffRows(f.variables(), oldVars, curr, prev,
                    (key, status) -> variableStatus.put(MemoryDiff.variableKey(f.id(), key), status));
            if (!oldVars.isEmpty()) {
                changed = true;
                deletedVariables.put(f.id(), List.copyOf(oldVars.values()));
            }
            frameStatus.put(f.id(), changed ? ChangeStatus.CHANGED : ChangeStatus.UNCHANGED);
        }

        for (DisplayableFrame gone : prevById.values()) {
            frameStatus.put(gone.id(), ChangeStatus.DELETED);
            deletedFrames.add(gone);
        }
    }

    private static void diffHeap(MemorySnapshot prev, MemorySnapshot curr,
            Map<String, ChangeStatus> structStatus, Map<String, Map<String, ChangeStatus>> fieldStatus,
            List<DisplayableStruct> deletedStructs) {

        // Insertion-ordered so the leftovers — the deleted structs — keep prev's heap order.
        Map<String, DisplayableStruct> prevById = new LinkedHashMap<>();
        for (DisplayableStruct s : prev.heap()) {
            prevById.put(s.id(), s);
        }

        for (DisplayableStruct struct : curr.heap()) {
            DisplayableStruct old = prevById.remove(struct.id());
            if (old == null) {
                structStatus.put(struct.id(), ChangeStatus.NEW);
                continue;
            }
            // Either side unexplored: contents unknown — never claim a change.
            if (!struct.explored() || !old.explored()) {
                structStatus.put(struct.id(), ChangeStatus.UNCHANGED);
                continue;
            }
            structStatus.put(struct.id(), diffStruct(old, struct, curr, prev, fieldStatus));
        }

        for (DisplayableStruct gone : prevById.values()) {
            structStatus.put(gone.id(), ChangeStatus.DELETED);
            deletedStructs.add(gone);
        }
    }

    private static ChangeStatus diffStruct(DisplayableStruct old, DisplayableStruct struct,
            MemorySnapshot curr, MemorySnapshot prev,
            Map<String, Map<String, ChangeStatus>> fieldStatus) {

        // The type carries neutral display info (e.g. an array's length); a change there is a change.
        boolean changed = !Objects.equals(old.type(), struct.type()) || old.omitted() != struct.omitted()
                || !Objects.equals(old.monitor(), struct.monitor());
        Map<String, ChangeStatus> byField = new HashMap<>();
        Map<String, DisplayableVariable> oldFields = byRowKey(old.variables());
        changed |= diffRows(struct.variables(), oldFields, curr, prev, byField::put);
        // Vanished fields only flag the struct CHANGED; there are no ghost rows inside a struct.
        if (!oldFields.isEmpty()) {
            changed = true;
        }
        if (!byField.isEmpty()) {
            fieldStatus.put(struct.id(), byField);
        }
        return changed ? ChangeStatus.CHANGED : ChangeStatus.UNCHANGED;
    }

    /**
     * Values compare so that: two box values are equal iff their strings match (the debuggee's
     * null is the string "null", so two nulls are equal); two references are equal iff they
     * resolve to the same target struct (both dangling counts as equal); different kinds of
     * {@link Value} are never equal.
     */
    static boolean valueEquals(Value a, Value b, MemorySnapshot da, MemorySnapshot db) {
        return switch (a) {
            case Value.BoxValue pa -> b instanceof Value.BoxValue pb && pa.value().equals(pb.value());
            case Value.Reference ra -> {
                if (!(b instanceof Value.Reference rb)) {
                    yield false;
                }
                Optional<DisplayableStruct> ta = da.resolve(ra);
                Optional<DisplayableStruct> tb = db.resolve(rb);
                // both dangling -> equal
                yield ta.isEmpty() || tb.isEmpty() ? ta.isEmpty() && tb.isEmpty() : ta.get().id().equals(tb.get().id());
            }
        };
    }
}
