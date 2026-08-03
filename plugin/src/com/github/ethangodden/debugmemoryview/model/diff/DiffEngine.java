package com.github.ethangodden.debugmemoryview.model.diff;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.annotation.Nullable;

import com.github.ethangodden.debugmemoryview.model.MemorySnapshot;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableFrame;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableStruct;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableThread;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableVariable;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.Value;

/**
 * Computes a {@link MemoryDiff} between two consecutive {@link MemorySnapshot}s on the same thread.
 * Only variables are diffed. A row's address is its container's opaque token (frame id or struct
 * id) plus its row key within that container; an address absent from the previous snapshot makes
 * the row NEW — so every row of a pushed frame or a fresh object is NEW, while a same-named local
 * in a different frame never matches. No prev (first suspend) or a thread switch makes every row
 * NEW. A struct that is unexplored on either side has unknown contents, and its rows are never
 * claimed as changed. References compare by RESOLVED TARGET — retargeting is the change on the
 * referring row; a target's own mutation shows on the target's rows. Two dangling references
 * compare equal; two unreadable values (both {@code BoxValue("?")}) compare equal.
 */
public final class DiffEngine {

    private DiffEngine() {
    }

    public static MemoryDiff diff(@Nullable MemorySnapshot prev, MemorySnapshot curr) {
        if (prev != null && !threadIds(prev).equals(threadIds(curr))) {
            prev = null; // a thread switch resets every address: all rows NEW
        }

        Map<String, DisplayableFrame> prevFrames = new HashMap<>();
        Map<String, DisplayableStruct> prevStructs = new HashMap<>();
        if (prev != null) {
            for (DisplayableFrame f : allFrames(prev)) {
                prevFrames.put(f.id(), f);
            }
            for (DisplayableStruct s : prev.heap()) {
                prevStructs.put(s.id(), s);
            }
        }

        Map<String, ChangeStatus> rows = new HashMap<>();
        for (DisplayableFrame f : allFrames(curr)) {
            DisplayableFrame old = prevFrames.get(f.id());
            diffRows(f.id(), f.variables(), old == null ? null : old.variables(), curr, prev, rows);
        }
        for (DisplayableStruct s : curr.heap()) {
            DisplayableStruct old = prevStructs.get(s.id());
            if (old != null && (!s.explored() || !old.explored())) {
                continue; // either side unexplored: contents unknown — never claim a change
            }
            diffRows(s.id(), s.variables(), old == null ? null : old.variables(), curr, prev, rows);
        }
        return new MemoryDiff(Map.copyOf(rows));
    }

    private static List<String> threadIds(MemorySnapshot s) {
        return s.threads().stream().map(DisplayableThread::id).toList();
    }

    /** All frames of a snapshot, across threads, in thread then stack order (top-of-stack first). */
    private static List<DisplayableFrame> allFrames(MemorySnapshot s) {
        return s.threads().stream().flatMap(t -> t.frames().stream()).toList();
    }

    /**
     * Diffs {@code currRows} against {@code oldRows} (null = the container itself is new),
     * recording each row's non-UNCHANGED status into {@code rows} under
     * {@link MemoryDiff#key}({@code containerId}, rowKey).
     */
    private static void diffRows(String containerId, List<DisplayableVariable> currRows,
            @Nullable List<DisplayableVariable> oldRows, MemorySnapshot curr,
            @Nullable MemorySnapshot prev, Map<String, ChangeStatus> rows) {
        Map<String, DisplayableVariable> oldByKey = new HashMap<>();
        if (oldRows != null) {
            List<String> oldKeys = MemoryDiff.rowKeys(oldRows);
            for (int i = 0; i < oldKeys.size(); i++) {
                oldByKey.put(oldKeys.get(i), oldRows.get(i));
            }
        }
        List<String> keys = MemoryDiff.rowKeys(currRows);
        for (int i = 0; i < keys.size(); i++) {
            DisplayableVariable old = oldByKey.get(keys.get(i));
            if (old == null) {
                rows.put(MemoryDiff.key(containerId, keys.get(i)), ChangeStatus.NEW);
            } else if (!valueEquals(currRows.get(i).value(), old.value(), curr, prev)) {
                rows.put(MemoryDiff.key(containerId, keys.get(i)), ChangeStatus.UPDATED);
            }
        }
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
