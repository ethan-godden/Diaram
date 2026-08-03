package com.github.ethangodden.debugmemoryview.model.diff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.ethangodden.debugmemoryview.model.MemorySnapshot;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableVariable;

/**
 * Per-variable change annotations for one {@link MemorySnapshot} relative to the previous one on
 * the same thread. The variable is the only diffed thing: a row is NEW when its address —
 * container id (frame id or struct id) plus {@link #rowKeys row key} — did not exist in the
 * previous snapshot, UPDATED when its value changed, UNCHANGED otherwise. Frames and structs carry
 * no status of their own, and nothing is tracked for what vanished.
 *
 * <p>Rows cover frame locals, object fields, array elements, and string chars uniformly (all are
 * {@link DisplayableVariable} rows keyed by their row key within their container). All keys are
 * opaque tokens — never a JVM id.
 */
public record MemoryDiff(Map<String, ChangeStatus> rows) { // containerId#rowKey -> NEW | UPDATED

	/** Composite key for a variable row: its container (frame or struct) id plus its row key. */
	public static String key(String containerId, String rowKey) {
		return containerId + "#" + rowKey; //$NON-NLS-1$
	}

	/**
	 * Diff keys for an ordered row list. A {@link DisplayableVariable} carries no separate symbol
	 * id, so a row's cross-snapshot identity is its label, disambiguated by occurrence index when
	 * the same label repeats (shadowed fields — pairing stays stable because the frontend emits
	 * fields in a stable order). The differ and the renderer must key rows through this one helper.
	 */
	public static List<String> rowKeys(List<DisplayableVariable> rows) {
		Map<String, Integer> seen = new HashMap<>();
		List<String> keys = new ArrayList<>(rows.size());
		for (DisplayableVariable row : rows) {
			int occurrence = seen.merge(row.label(), Integer.valueOf(1), Integer::sum).intValue();
			keys.add(occurrence == 1 ? row.label() : row.label() + "#" + occurrence); //$NON-NLS-1$
		}
		return keys;
	}

	/** Absent rows are UNCHANGED; only NEW and UPDATED are recorded. */
	public ChangeStatus statusOf(String containerId, String rowKey) {
		return rows.getOrDefault(key(containerId, rowKey), ChangeStatus.UNCHANGED);
	}
}
