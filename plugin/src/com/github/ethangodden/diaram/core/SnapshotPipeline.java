package com.github.ethangodden.diaram.core;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com.github.ethangodden.diaram.Activator;
import com.github.ethangodden.diaram.core.extract.SnapshotExtractor;
import com.github.ethangodden.diaram.model.MemorySnapshot;
import com.github.ethangodden.diaram.model.diff.DiffEngine;
import com.github.ethangodden.diaram.model.diff.MemoryDiff;

/**
 * Debounced extraction pipeline between the debug listeners and the view.
 *
 * Threading rules:
 * <ul>
 * <li>Trigger methods are called from the UI thread (context service) or the
 * debug event dispatch thread; they make NO JDI wire calls — they capture
 * debug-model references, bump the request sequence and (re)schedule the Job.</li>
 * <li>Every JDI call happens inside {@link SnapshotJob#run} on a Jobs-framework
 * worker thread. The baseline map is confined to that thread (the Jobs
 * framework never runs one Job instance concurrently with itself).</li>
 * <li>Results are marshalled to consumers with {@code Display.asyncExec}, gated
 * by a final sequence check so a superseded snapshot is never displayed.</li>
 * </ul>
 */
public final class SnapshotPipeline {

    private static final long DEBOUNCE_MS = 150;

    private record Request(long seq, IJavaThread thread) {
    }

    /** One thread's cached suspend state, keyed by threadKey; the parts always move together. */
    private record Baseline(MemorySnapshot snapshot, MemoryDiff diff, long generation) {
    }

    private final AtomicLong seq = new AtomicLong();
    private volatile Request current;
    // Guards the seq-bump + current store so concurrent triggers (context service
    // on the UI thread vs suspendFallback on the dispatch thread) can never leave
    // 'current' holding a superseded request, which would silently drop a suspend.
    private final Object requestLock = new Object();
    private volatile ExtractionLimits limits = ExtractionLimits.defaults();
    private final SnapshotJob job = new SnapshotJob();
    private final CopyOnWriteArrayList<ISnapshotConsumer> consumers = new CopyOnWriteArrayList<>();

    // Identity caches, safe on any thread (the JDT debug model does not override
    // equals/hashCode, and building a key is done once, on the Job thread).
    private final ConcurrentHashMap<IDebugTarget, String> targetKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IJavaThread, String> threadKeys = new ConcurrentHashMap<>();
    // Suspend generation per threadKey: bumped on RESUME. A trigger whose thread's
    // generation still matches the stored baseline is a same-suspend re-trigger
    // and takes the fast path (republish the cache, no JDI traffic).
    private final ConcurrentHashMap<String, Long> suspendGenerations = new ConcurrentHashMap<>();
    // Baseline removals requested from the event thread, executed on the Job thread.
    private final ConcurrentLinkedQueue<String> pendingBaselineCleanups = new ConcurrentLinkedQueue<>();

    private volatile String lastPublishedTargetKey;
    // Job-thread-confined state (accessed only inside SnapshotJob.run).
    private final Map<String, Baseline> baselines = new HashMap<>();

    // ---- triggers (UI thread / debug event dispatch thread; no JDI wire calls) ----

    public void trigger(@Nullable IJavaThread thread) {
        if (thread == null) {
            return;
        }
        synchronized (requestLock) {
            current = new Request(seq.incrementAndGet(), thread);
        }
        job.cancel(); // flags a running extraction's monitor; no-op when idle
        job.schedule(DEBOUNCE_MS); // re-arms the fuse: bursts collapse to one extraction
    }

    /** Raw SUSPEND event; matters when the Debug view is closed. Debounce dedupes vs the context event. */
    public void suspendFallback(@Nullable IJavaThread thread) {
        trigger(thread);
    }

    // Under the request lock: if the live request matches, mark it superseded (a seq
    // bump so any in-flight publish is dropped), cancel the Job, and forget it.
    private void invalidateCurrentMatching(Predicate<Request> doomed) {
        synchronized (requestLock) {
            Request req = current;
            if (req != null && doomed.test(req)) {
                seq.incrementAndGet();
                job.cancel();
                current = null;
            }
        }
    }

    private void bumpAndNotifyResumed(String threadKey) {
        suspendGenerations.merge(threadKey, Long.valueOf(1), Long::sum);
        uiPost(c -> c.threadResumed(threadKey));
    }

    public void threadResumed(IJavaThread thread) {
        invalidateCurrentMatching(req -> req.thread() == thread);
        String threadKey = threadKeys.get(thread);
        if (threadKey == null) {
            return; // never extracted, so never rendered
        }
        bumpAndNotifyResumed(threadKey);
    }

    /**
     * A target-level RESUME (e.g. Resume on the debug-target node) fires no
     * per-thread RESUME events, so every extracted thread of the target must be
     * treated as resumed here — otherwise the next suspend would match the stored
     * baseline generation and republish a stale snapshot via the fast path.
     */
    public void targetResumed(IDebugTarget target) {
        invalidateCurrentMatching(req -> req.thread().getDebugTarget() == target);
        String targetKey = targetKeys.get(target);
        if (targetKey == null) {
            return; // never extracted from this target
        }
        String prefix = targetKey + "/"; //$NON-NLS-1$
        for (String threadKey : threadKeys.values()) {
            if (threadKey.startsWith(prefix)) {
                bumpAndNotifyResumed(threadKey);
            }
        }
    }

    /** A dying thread's key caches and Job-confined baselines must not outlive it. */
    public void threadTerminated(IJavaThread thread) {
        invalidateCurrentMatching(req -> req.thread() == thread);
        String threadKey = threadKeys.remove(thread);
        if (threadKey == null) {
            return; // never extracted, so nothing retained
        }
        suspendGenerations.remove(threadKey);
        pendingBaselineCleanups.add(threadKey);
        job.schedule(DEBOUNCE_MS); // ensures the Job-confined baselines get dropped
    }

    public void targetTerminated(IDebugTarget target) {
        invalidateCurrentMatching(req -> req.thread().getDebugTarget() == target);
        String targetKey = targetKeys.remove(target);
        if (targetKey == null) {
            return; // never extracted from this target
        }
        String prefix = targetKey + "/"; //$NON-NLS-1$
        threadKeys.entrySet().removeIf(e -> e.getValue().startsWith(prefix));
        suspendGenerations.keySet().removeIf(k -> k.startsWith(prefix));
        pendingBaselineCleanups.add(targetKey);
        job.schedule(DEBOUNCE_MS); // ensures the Job-confined baselines get dropped
        if (targetKey.equals(lastPublishedTargetKey)) {
            lastPublishedTargetKey = null;
            uiPost(c -> c.cleared("terminated")); //$NON-NLS-1$
        }
    }

    public void clear(String reason) {
        synchronized (requestLock) {
            seq.incrementAndGet();
            job.cancel();
            current = null;
        }
        lastPublishedTargetKey = null;
        uiPost(c -> c.cleared(reason));
    }

    // ---- configuration / lifecycle ----------------------------------------

    public void addConsumer(@Nullable ISnapshotConsumer consumer) {
        if (consumer != null) {
            consumers.addIfAbsent(consumer);
        }
    }

    public void removeConsumer(ISnapshotConsumer consumer) {
        consumers.remove(consumer);
    }

    /** Applied on the next extraction; in-flight runs keep the limits they started with. */
    public void setLimits(@Nullable ExtractionLimits newLimits) {
        if (newLimits != null) {
            this.limits = newLimits;
        }
    }

    public void dispose() {
        synchronized (requestLock) {
            seq.incrementAndGet();
            current = null;
            job.cancel();
        }
        consumers.clear();
        targetKeys.clear();
        threadKeys.clear();
        suspendGenerations.clear();
        pendingBaselineCleanups.clear();
        // The Job-confined baselines die with this instance.
    }

    // ---- the Job -----------------------------------------------------------

    private final class SnapshotJob extends Job {

        SnapshotJob() {
            super("Extracting memory diagram"); //$NON-NLS-1$
            setSystem(true);
            setPriority(Job.SHORT);
        }

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            drainBaselineCleanups();
            Request req = current;
            if (req == null || req.seq() != seq.get()) {
                return Status.CANCEL_STATUS; // superseded before start
            }
            IJavaThread thread = req.thread();
            try {
                IDebugTarget target = thread.getDebugTarget();
                if (target == null || target.isTerminated() || target.isDisconnected()
                        || !thread.isSuspended()) {
                    return Status.CANCEL_STATUS;
                }
                String targetKey = targetKeyOf(target);
                String threadKey = threadKeyOf(targetKey, thread);
                long generation = suspendGenerations.getOrDefault(threadKey, Long.valueOf(0)).longValue();
                // Same-suspend fast path: a re-trigger within one suspend republishes
                // the cached snapshot + diff instead of re-extracting (which would also
                // wrongly wipe the change highlighting by re-baselining).
                Baseline baseline = baselines.get(threadKey);
                if (baseline != null && baseline.generation() == generation) {
                    publish(req.seq(), baseline.snapshot(), baseline.diff());
                    return Status.OK_STATUS;
                }

                MemorySnapshot snapshot = new SnapshotExtractor(limits, monitor)
                        .extract(thread, targetKey, threadKey);
                if (req.seq() != seq.get() || monitor.isCanceled()) {
                    return Status.CANCEL_STATUS; // superseded during the run; never store or show
                }
                MemorySnapshot previous = baseline != null ? baseline.snapshot() : null;
                MemoryDiff diff = DiffEngine.diff(previous, snapshot);
                baselines.put(threadKey, new Baseline(snapshot, diff, generation));
                publish(req.seq(), snapshot, diff);
                return Status.OK_STATUS;
            } catch (OperationCanceledException e) {
                return Status.CANCEL_STATUS;
            } catch (DebugException e) {
                if (!isStaleContext(thread, e)) {
                    log("Memory diagram extraction failed", e); //$NON-NLS-1$
                }
                return Status.CANCEL_STATUS; // UI keeps its current state; RESUME/TERMINATE already handled it
            } catch (RuntimeException e) {
                if (e.getClass().getName().startsWith("com.sun.jdi.")) { //$NON-NLS-1$
                    return Status.CANCEL_STATUS; // VM died mid-walk, unwrapped by JDT
                }
                log("Unexpected error extracting memory diagram", e); //$NON-NLS-1$
                uiPost(c -> c.cleared("diagram unavailable")); //$NON-NLS-1$
                return Status.CANCEL_STATUS;
            }
        }
    }

    private void drainBaselineCleanups() {
        String key;
        while ((key = pendingBaselineCleanups.poll()) != null) {
            // An exact threadKey (thread death) or a targetKey whose threads all
            // died with it (baselines are keyed "targetKey/thread…"); each removal
            // is a no-op for the other kind of key.
            baselines.remove(key);
            String prefix = key + "/"; //$NON-NLS-1$
            baselines.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    private static boolean isStaleContext(IJavaThread thread, DebugException e) {
        int code = e.getStatus() != null ? e.getStatus().getCode() : 0;
        if (code == IJavaThread.ERR_THREAD_NOT_SUSPENDED
                || code == IJavaThread.ERR_INCOMPATIBLE_THREAD_STATE
                || code == IJavaStackFrame.ERR_INVALID_STACK_FRAME) {
            return true;
        }
        IDebugTarget target = thread.getDebugTarget();
        return target == null || target.isTerminated() || target.isDisconnected()
                || !thread.isSuspended();
    }

    // ---- keys (built once, on the Job thread, then opaque strings) ---------

    private String targetKeyOf(IDebugTarget target) {
        return targetKeys.computeIfAbsent(target, t -> {
            String name = null;
            ILaunch launch = t.getLaunch();
            ILaunchConfiguration configuration = launch == null ? null : launch.getLaunchConfiguration();
            if (configuration != null) {
                name = configuration.getName();
            }
            if (name == null) {
                try {
                    name = t.getName();
                } catch (DebugException e) {
                    name = "target"; //$NON-NLS-1$
                }
            }
            return name + "#" + System.identityHashCode(t); //$NON-NLS-1$
        });
    }

    private String threadKeyOf(String targetKey, IJavaThread thread) {
        return threadKeys.computeIfAbsent(thread, t -> {
            String name;
            try {
                name = t.getName();
            } catch (DebugException e) {
                name = "thread"; //$NON-NLS-1$
            }
            return targetKey + "/" + name + "#" + System.identityHashCode(t); //$NON-NLS-1$ //$NON-NLS-2$
        });
    }

    // ---- marshalling to the UI thread ---------------------------------------

    private void publish(long publishedSeq, MemorySnapshot snapshot, MemoryDiff diff) {
        Display display = displayOrNull();
        if (display == null) {
            return;
        }
        display.asyncExec(() -> {
            if (publishedSeq != seq.get()) {
                return; // superseded while queued
            }
            lastPublishedTargetKey = snapshot.targetId();
            for (ISnapshotConsumer consumer : consumers) {
                consumer.snapshotReady(snapshot, diff);
            }
        });
    }

    private void uiPost(Consumer<ISnapshotConsumer> call) {
        Display display = displayOrNull();
        if (display == null) {
            return;
        }
        display.asyncExec(() -> {
            for (ISnapshotConsumer consumer : consumers) {
                call.accept(consumer);
            }
        });
    }

    private static Display displayOrNull() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return null;
        }
        Display display = PlatformUI.getWorkbench().getDisplay();
        return display == null || display.isDisposed() ? null : display;
    }

    private static void log(String message, Throwable t) {
        Activator activator = Activator.getDefault();
        if (activator != null) {
            activator.getLog().log(new Status(IStatus.ERROR, Activator.PLUGIN_ID, message, t));
        }
    }
}
