package com.github.ethangodden.diaram.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One coherent picture of a suspended debuggee: threads (each a call stack)
 * sharing one heap. Immutable, so snapshots are safe to hand between the
 * extraction job and the UI thread.
 *
 * <p>
 * A {@link Value.Reference} is opaque: only a {@link Builder} can mint one, and
 * {@link #resolve} is the only way through one, so how targets are addressed
 * never leaves this file. A reference whose target is absent from the snapshot
 * resolves empty — that is what "dangling" means.
 */
public final class MemorySnapshot {
	private final String targetId;
	private final List<DisplayableThread> threads;
	private final List<DisplayableStruct> heap; // discovery order
	private final Map<String, DisplayableStruct> heapById;

	private MemorySnapshot(Builder b) {
		this.targetId = b.targetId;
		this.threads = List.copyOf(b.threads);
		this.heap = List.copyOf(b.heap.values());
		this.heapById = Map.copyOf(b.heap);
	}

	/**
	 * Starts a snapshot of the debug session {@code targetId}. The id is baked into
	 * every minted reference token, so resolving a reference against another
	 * target's snapshot is a clean miss (dangling), never a chance id collision.
	 */
	public static Builder builder(String targetId) {
		return new Builder(targetId);
	}

	/** The only door through a Reference. Empty = dangling. */
	public Optional<DisplayableStruct> resolve(Value.Reference ref) {
		return Optional.ofNullable(heapById.get(ref.target));
	}

	/** The debug session this snapshot came from; see {@link #builder(String)}. */
	public String targetId() {
		return targetId;
	}

	/** The suspended threads, in the order the frontend supplied them. */
	public List<DisplayableThread> threads() {
		return threads;
	}

	/**
	 * The thread whose stack the view renders — the pipeline walks exactly one suspended thread
	 * per snapshot. The single seam to widen when snapshots grow multiple threads (one stack
	 * column per thread).
	 */
	public Optional<DisplayableThread> renderedThread() {
		return threads.stream().findFirst();
	}

	/** Every heap struct (statics classes included), in discovery order. */
	public List<DisplayableStruct> heap() {
		return heap;
	}


	/** What a variable holds: a display string or a handle to a struct. */
	public sealed interface Value {

		/**
		 * A display string. The debuggee's null is {@code new BoxValue("null")} — the row keeps
		 * the variable's declared object type.
		 */
		record BoxValue(String value) implements Value {}

		/**
		 * Opaque handle to a struct; meaningless except via
		 * {@link MemorySnapshot#resolve}.
		 */
		final class Reference implements Value {
			private final String target;

			private Reference(String target) {
				this.target = target;
			}

			@Override
			public boolean equals(Object o) {
				return o instanceof Reference r && target.equals(r.target);
			}

			@Override
			public int hashCode() {
				return target.hashCode();
			}
		}
	}

	/**
	 * One row in a frame or struct. {@code value} is never Java {@code null} — the debuggee's null
	 * is {@code new Value.BoxValue("null")}, with {@code type} still the declared object type. A
	 * null {@code type} marks a display-only content row (e.g. an enum constant name), whose
	 * {@code value} is unused (conventionally {@code new Value.BoxValue("null")}).
	 */
	public record DisplayableVariable(String label, String type, Value value) {}

	/**
	 * Intrinsic-lock state for one struct; null on the vast majority of structs.
	 * {@code heldBy} is the id of the thread currently inside
	 * {@code synchronized(thisObject)}; {@code waiters} are the ids of threads
	 * parked in {@code thisObject.wait()}.
	 */
	public record Monitor(String heldBy, List<String> waiters) {
		public Monitor {
			waiters = List.copyOf(waiters);
		}
	}

	/**
	 * Every heap struct: object, array (positional "0", "1", … labels), string,
	 * or the statics of a class.
	 */
	public record DisplayableStruct(
			String id,
			String type,
			List<DisplayableVariable> variables,
			boolean explored, // false = unexpanded stub, not "object with no fields"
			int omitted, // rows lost to caps: renders as "+ N more"
			Monitor monitor
	) {
		public DisplayableStruct {
			variables = List.copyOf(variables);
		}
	}

	/**
	 * One stack entry. A non-null {@code note} ("(native method)", …) is shown in
	 * place of the variable rows.
	 */
	public record DisplayableFrame(
			String id,
			String label,
			List<DisplayableVariable> variables,
			String note
	) {
		public DisplayableFrame {
			variables = List.copyOf(variables);
		}
	}

	/**
	 * One call stack, {@code frames} top-of-stack first. {@code state} is a
	 * frontend-supplied display label ("running", "waiting", …);
	 * {@code contendedOn} (nullable) is the struct whose monitor this thread is
	 * stuck waiting to acquire.
	 */
	public record DisplayableThread(
			String id,
			String name,
			String state,
			List<DisplayableFrame> frames,
			Value.Reference contendedOn
	) {
		public DisplayableThread {
			frames = List.copyOf(frames);
		}
	}

	/** The single ingestion point — and the only minter of References. */
	public static final class Builder {
		private final String targetId;
		private final List<DisplayableThread> threads = new ArrayList<>();
		private final Map<String, DisplayableStruct> heap = new LinkedHashMap<>();

		private Builder(String targetId) {
			this.targetId = Objects.requireNonNull(targetId);
		}

		/**
		 * Mints a handle to {@code structId}. Pure — it claims no discovery slot, and
		 * a target nobody ever provides is simply dangling.
		 */
		public Value.Reference reference(String structId) {
			return new Value.Reference(token(structId));
		}

		/**
		 * Claims {@code id}'s place in discovery order with an unexplored stub;
		 * {@link #fill} replaces it. A no-op if the id was already provided.
		 */
		public Builder reserve(String id, String type) {
			heap.putIfAbsent(token(id), new DisplayableStruct(id, type, List.of(), false, 0, null));
			return this;
		}

		/** Replaces the stub (or an earlier fill) for {@code struct.id()}. */
		public Builder fill(DisplayableStruct struct) {
			heap.put(token(struct.id()), struct);
			return this;
		}

		/** Appends a thread; threads keep this call order. */
		public Builder thread(DisplayableThread thread) {
			threads.add(thread);
			return this;
		}

		/** Freezes the accumulated threads and heap into an immutable snapshot. */
		public MemorySnapshot build() {
			return new MemorySnapshot(this);
		}

		private String token(String structId) {
			return targetId + "/" + structId;
		}
	}

}
