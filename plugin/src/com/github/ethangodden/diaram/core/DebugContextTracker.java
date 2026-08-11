package com.github.ethangodden.diaram.core;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.contexts.DebugContextEvent;
import org.eclipse.debug.ui.contexts.IDebugContextListener;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkbenchPartSite;

/**
 * Feeds the {@link SnapshotPipeline} from two sources: the part-scoped debug
 * context service (frame/thread selection, multi-session focus — fires on the
 * UI thread) and the raw DebugPlugin event stream (prompt RESUME/TERMINATE, and
 * a SUSPEND fallback for when the Debug view is closed — fires on the debug
 * event dispatch thread).
 *
 * No method here makes a JDI wire call: instanceof checks and
 * {@code IStackFrame#getThread()} are local; anything heavier (like
 * {@code getTopStackFrame()}) is deferred into the pipeline's Job.
 */
public final class DebugContextTracker implements IDebugContextListener, IDebugEventSetListener {

    private volatile IWorkbenchPartSite site;
    private volatile SnapshotPipeline pipeline;

    /** Call from the view's createPartControl, on the UI thread. */
    public void install(IWorkbenchPartSite partSite, SnapshotPipeline snapshotPipeline) {
        this.site = partSite;
        this.pipeline = snapshotPipeline;
        DebugUITools.addPartDebugContextListener(partSite, this);
        DebugPlugin.getDefault().addDebugEventListener(this);
        seedFromCurrentContext(partSite);
    }

    /** A view opened mid-suspend renders immediately instead of waiting for the next context event. */
    private void seedFromCurrentContext(IWorkbenchPartSite partSite) {
        Object element = DebugUITools.getPartDebugContext(partSite);
        if (element == null) {
            ISelection selection = DebugUITools.getDebugContextManager()
                    .getContextService(partSite.getWorkbenchWindow()).getActiveContext();
            if (selection instanceof IStructuredSelection structured && !structured.isEmpty()) {
                element = structured.getFirstElement();
            }
        }
        if (element != null) {
            resolveAndTrigger(element); // a non-Java seed just leaves the initial placeholder
        }
    }

    /** Call from the view part's dispose. */
    public void dispose() {
        IWorkbenchPartSite partSite = site;
        if (partSite != null) {
            DebugUITools.removePartDebugContextListener(partSite, this);
            site = null;
        }
        DebugPlugin debugPlugin = DebugPlugin.getDefault();
        if (debugPlugin != null) {
            debugPlugin.removeDebugEventListener(this);
        }
        pipeline = null;
    }

    // ---- IDebugContextListener (UI thread) ---------------------------------

    @Override
    public void debugContextChanged(DebugContextEvent event) {
        SnapshotPipeline p = pipeline;
        if (p == null || (event.getFlags() & DebugContextEvent.ACTIVATED) == 0) {
            return;
        }
        ISelection selection = event.getContext();
        if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty()) {
            p.clear("no debug context"); //$NON-NLS-1$
            return;
        }
        if (!resolveAndTrigger(structured.getFirstElement())) {
            p.clear("not a Java debug context"); //$NON-NLS-1$
        }
    }

    private boolean resolveAndTrigger(Object element) {
        SnapshotPipeline p = pipeline;
        if (p == null) {
            return true;
        }
        if (element instanceof IJavaStackFrame frame) {
            p.trigger((IJavaThread) frame.getThread());
            return true;
        }
        if (element instanceof IJavaThread thread) {
            p.trigger(thread);
            return true;
        }
        if (element instanceof IAdaptable adaptable) {
            IJavaStackFrame frame = adaptable.getAdapter(IJavaStackFrame.class);
            if (frame != null) {
                p.trigger((IJavaThread) frame.getThread());
                return true;
            }
            IJavaThread thread = adaptable.getAdapter(IJavaThread.class);
            if (thread != null) {
                p.trigger(thread);
                return true;
            }
        }
        return false; // e.g. an ILaunch or a non-Java target
    }

    // ---- IDebugEventSetListener (debug event dispatch thread) ---------------

    @Override
    public void handleDebugEvents(DebugEvent[] events) {
        SnapshotPipeline p = pipeline;
        if (p == null) {
            return;
        }
        for (DebugEvent event : events) {
            if (event.isEvaluation()) {
                continue; // detail formatters / expression evaluation: no flicker, no re-extraction
            }
            Object source = event.getSource();
            switch (event.getKind()) {
                case DebugEvent.RESUME -> {
                    if (source instanceof IJavaThread thread) {
                        p.threadResumed(thread);
                    } else if (source instanceof IDebugTarget target) {
                        // Resuming the whole target fires no per-thread RESUME events.
                        p.targetResumed(target);
                    }
                }
                case DebugEvent.SUSPEND -> {
                    if (source instanceof IJavaThread thread && isHonoredSuspendDetail(event.getDetail())) {
                        p.suspendFallback(thread); // debounce dedupes vs the context activation
                    }
                }
                case DebugEvent.TERMINATE -> {
                    if (source instanceof IDebugTarget target) {
                        p.targetTerminated(target);
                    } else if (source instanceof IJavaThread thread) {
                        p.threadTerminated(thread); // a dead thread's baselines must not leak
                    }
                }
                default -> {
                    // CREATE/CHANGE/MODEL_SPECIFIC are irrelevant here
                }
            }
        }
    }

    private static boolean isHonoredSuspendDetail(int detail) {
        return detail == DebugEvent.BREAKPOINT || detail == DebugEvent.STEP_END
                || detail == DebugEvent.CLIENT_REQUEST;
    }
}
