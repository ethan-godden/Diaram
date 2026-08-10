package com.github.ethangodden.debugmemoryview.ui;

import java.util.Objects;
import java.util.function.IntConsumer;

import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.draw2d.FigureCanvas;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.PageBook;
import org.eclipse.ui.part.ViewPart;

import com.github.ethangodden.debugmemoryview.Activator;
import com.github.ethangodden.debugmemoryview.PreferenceConstants;
import com.github.ethangodden.debugmemoryview.core.DebugContextTracker;
import com.github.ethangodden.debugmemoryview.core.ExtractionLimits;
import com.github.ethangodden.debugmemoryview.core.ISnapshotConsumer;
import com.github.ethangodden.debugmemoryview.core.SnapshotPipeline;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableThread;
import com.github.ethangodden.debugmemoryview.model.diff.MemoryDiff;
import com.github.ethangodden.debugmemoryview.render.DiagramController;
import com.github.ethangodden.debugmemoryview.render.PluginConfig;

/**
 * The Memory Diagram view: a PageBook flipping between a placeholder label and
 * the Draw2d canvas owned by {@link DiagramController}. This part owns the
 * {@link SnapshotPipeline} / {@link DebugContextTracker} pair and consumes
 * their output; every {@link ISnapshotConsumer} callback arrives on the SWT UI
 * thread.
 */
public class MemoryDiagramView extends ViewPart implements ISnapshotConsumer {

    private static final String REASON_TERMINATED = "terminated"; //$NON-NLS-1$

    private static final int[] HEAP_OBJECT_CHOICES = { 100, 200, 500 };
    private static final int[] FIELD_CHOICES = { 8, 16, 32 };
    private static final int[] ARRAY_ELEMENT_CHOICES = { 10, 25, 50 };

    private final PluginConfig config = new PluginConfig();

    private PageBook pageBook;
    private Label placeholder;
    private FigureCanvas canvas;
    private DiagramController controller;

    private SnapshotPipeline pipeline;
    private DebugContextTracker tracker;
    private IPropertyChangeListener preferenceListener;

    private Action pinAction;
    private Action staticsAction;
    private Action highlightAction;

    // Pinned = the displayed diagram is frozen; newer snapshots are cached only.
    private boolean pinned;
    private MemorySnapshot displayed;
    private MemorySnapshot pendingSnapshot;
    private MemoryDiff pendingDiff;

    @Override
    public void init(IViewSite site, @Nullable IMemento memento) throws PartInitException {
        super.init(site, memento);
        config.restore(memento);
    }

    @Override
    public void saveState(IMemento memento) {
        super.saveState(memento);
        config.save(memento);
    }

    @Override
    public void createPartControl(Composite parent) {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        // The preference store is the master copy of the highlight toggle; the
        // per-view flag is kept identical so their AND never surprises.
        config.highlightChanges = store.getBoolean(PreferenceConstants.PREF_HIGHLIGHT_CHANGES);

        pageBook = new PageBook(parent, SWT.NONE);

        placeholder = new Label(pageBook, SWT.CENTER | SWT.WRAP);
        placeholder.setText(
                "No suspended debug session. Suspend a Java thread (breakpoint or step) to see its memory diagram.");

        canvas = new FigureCanvas(pageBook, SWT.DOUBLE_BUFFERED);
        canvas.setScrollBarVisibility(FigureCanvas.NEVER);
        canvas.getViewport().setContentsTracksWidth(true);
        canvas.getViewport().setContentsTracksHeight(true);
        // Bound to the canvas: colors/fonts are disposed with the control.
        LocalResourceManager resources = new LocalResourceManager(JFaceResources.getResources(), canvas);
        controller = new DiagramController(canvas, resources, config);
        canvas.setContents(controller.getRootFigure());
        canvas.addMouseWheelListener(controller::handleWheel);
        // Trackpad two-finger horizontal swipes come through as a distinct SWT
        // event (not the MouseWheelListener); route them to the whole-view h-scroll.
        canvas.addListener(SWT.MouseHorizontalWheel, event -> controller.handleHorizontalWheel(event.count));

        makeActions(store);
        contributeToActionBars();

        preferenceListener = event -> preferenceChanged(store, event.getProperty());
        store.addPropertyChangeListener(preferenceListener);

        pageBook.showPage(placeholder);

        pipeline = new SnapshotPipeline();
        pipeline.setLimits(ExtractionLimits.defaults().withMaxObjects(config.maxHeapObjectsRendered));
        // Consumer first: install() seeds from the current debug context and may
        // publish immediately (view opened mid-suspend).
        pipeline.addConsumer(this);
        tracker = new DebugContextTracker();
        tracker.install(getSite(), pipeline);
    }

    @Override
    public void setFocus() {
        if (canvas != null && !canvas.isDisposed()) {
            canvas.setFocus();
        }
    }

    @Override
    public void dispose() {
        if (tracker != null) {
            tracker.dispose();
            tracker = null;
        }
        if (pipeline != null) {
            pipeline.removeConsumer(this);
            pipeline.dispose();
            pipeline = null;
        }
        if (preferenceListener != null) {
            Activator activator = Activator.getDefault();
            if (activator != null) {
                activator.getPreferenceStore().removePropertyChangeListener(preferenceListener);
            }
            preferenceListener = null;
        }
        super.dispose();
        // The LocalResourceManager and all figures die with the canvas.
    }

    // ---- ISnapshotConsumer (UI thread) --------------------------------------

    @Override
    public void snapshotReady(MemorySnapshot snapshot, MemoryDiff diff) {
        if (isGone()) {
            return;
        }
        if (pinned) {
            pendingSnapshot = snapshot;
            pendingDiff = diff;
            // The pinned thread re-suspended: drop the "Running…" veil that
            // threadResumed() painted, while keeping the frozen diagram on screen.
            if (displayed != null && Objects.equals(threadIdOf(snapshot), threadIdOf(displayed))) {
                controller.setRunning(false);
            }
            return;
        }
        display(snapshot, diff);
    }

    @Override
    public void threadResumed(String threadToken) {
        if (isGone()) {
            return;
        }
        // Resumes are broadcast for every previously-extracted thread; only the
        // one whose snapshot is on screen grays the diagram.
        if (displayed != null && Objects.equals(threadIdOf(displayed), threadToken)) {
            controller.setRunning(true);
        }
    }

    /** The rendered thread's id; single-thread policy lives in {@link MemorySnapshot#renderedThread}. */
    private static String threadIdOf(MemorySnapshot snapshot) {
        return snapshot.renderedThread().map(DisplayableThread::id).orElse(null);
    }

    @Override
    public void cleared(String reason) {
        if (isGone()) {
            return;
        }
        boolean terminated = REASON_TERMINATED.equals(reason);
        if (pinned && !terminated) {
            return; // e.g. a process node selected in the Debug view: the pin holds
        }
        displayed = null;
        pendingSnapshot = null;
        pendingDiff = null;
        pinned = false;
        pinAction.setChecked(false); // nothing left to pin
        controller.setRunning(false);
        if (terminated) {
            controller.clearSession();
        } else {
            controller.clear();
        }
        pageBook.showPage(placeholder);
    }

    private void display(MemorySnapshot snapshot, MemoryDiff diff) {
        displayed = snapshot;
        controller.setRunning(false);
        controller.setSnapshot(snapshot, diff); // setSnapshot defaults a null diff to all-NEW
        pageBook.showPage(canvas);
    }

    private boolean isGone() {
        return canvas == null || canvas.isDisposed();
    }

    // ---- preference store ----------------------------------------------------

    private void preferenceChanged(IPreferenceStore store, String property) {
        if (isGone()) {
            return;
        }
        if (PreferenceConstants.PREF_HIGHLIGHT_CHANGES.equals(property)) {
            boolean on = store.getBoolean(PreferenceConstants.PREF_HIGHLIGHT_CHANGES);
            config.highlightChanges = on;
            highlightAction.setChecked(on);
        }
        controller.refresh(); // repick colors / highlight state
    }

    // ---- actions --------------------------------------------------------------

    private void makeActions(IPreferenceStore store) {
        ISharedImages sharedImages = PlatformUI.getWorkbench().getSharedImages();

        pinAction = new Action("Pin", IAction.AS_CHECK_BOX) {
            @Override
            public void run() {
                pinned = isChecked();
                if (!pinned && pendingSnapshot != null) {
                    MemorySnapshot snapshot = pendingSnapshot;
                    MemoryDiff diff = pendingDiff;
                    pendingSnapshot = null;
                    pendingDiff = null;
                    display(snapshot, diff);
                }
            }
        };
        pinAction.setToolTipText("Pin the current diagram (newer snapshots are cached, not shown)");
        pinAction.setImageDescriptor(DebugUITools.getImageDescriptor(IDebugUIConstants.IMG_LCL_LOCK));

        staticsAction = new Action("Statics", IAction.AS_CHECK_BOX) {
            @Override
            public void run() {
                controller.setShowStatics(isChecked());
            }
        };
        staticsAction.setToolTipText("Show the statics section in the heap column");
        staticsAction.setChecked(config.showStatics);

        Action expandAllAction = new Action("Expand All") {
            @Override
            public void run() {
                controller.expandAll();
            }
        };
        expandAllAction.setToolTipText("Expand all frames and heap objects");
        expandAllAction.setImageDescriptor(sharedImages.getImageDescriptor(ISharedImages.IMG_ELCL_EXPANDALL));

        Action collapseAllAction = new Action("Collapse All") {
            @Override
            public void run() {
                controller.collapseAll();
            }
        };
        collapseAllAction.setToolTipText("Collapse all frames and heap objects");
        collapseAllAction.setImageDescriptor(sharedImages.getImageDescriptor(ISharedImages.IMG_ELCL_COLLAPSEALL));

        Action refreshAction = new Action("Refresh") {
            @Override
            public void run() {
                controller.refresh();
            }
        };
        refreshAction.setToolTipText("Re-render the diagram (picks up theme changes)");

        highlightAction = new Action("Highlight Changes", IAction.AS_CHECK_BOX) {
            @Override
            public void run() {
                boolean on = isChecked();
                config.highlightChanges = on;
                // The store change fires preferenceChanged(), which refreshes.
                store.setValue(PreferenceConstants.PREF_HIGHLIGHT_CHANGES, on);
            }
        };
        highlightAction.setToolTipText("Color items that changed since the previous suspend");
        highlightAction.setChecked(store.getBoolean(PreferenceConstants.PREF_HIGHLIGHT_CHANGES));

        IActionBars bars = getViewSite().getActionBars();
        IToolBarManager toolbar = bars.getToolBarManager();
        toolbar.add(pinAction);
        toolbar.add(staticsAction);
        toolbar.add(new Separator());
        toolbar.add(expandAllAction);
        toolbar.add(collapseAllAction);
        toolbar.add(refreshAction);
    }

    private void contributeToActionBars() {
        IActionBars bars = getViewSite().getActionBars();
        IMenuManager menu = bars.getMenuManager();
        menu.add(radioGroup("Max Heap Objects", HEAP_OBJECT_CHOICES, config.maxHeapObjectsRendered,
                this::applyHeapObjectCap));
        menu.add(radioGroup("Max Fields per Object", FIELD_CHOICES, config.maxFieldsPerObjectRendered,
                this::applyFieldCap));
        menu.add(radioGroup("Max Array Elements", ARRAY_ELEMENT_CHOICES, config.maxArrayElementsRendered,
                this::applyArrayElementCap));
        menu.add(new Separator());
        menu.add(highlightAction);
        bars.updateActionBars();
    }

    private MenuManager radioGroup(String label, int[] choices, int current, IntConsumer apply) {
        MenuManager group = new MenuManager(label);
        for (int choice : choices) {
            Action action = new Action(Integer.toString(choice), IAction.AS_RADIO_BUTTON) {
                @Override
                public void run() {
                    if (isChecked()) { // radio deselection also calls run()
                        apply.accept(choice);
                    }
                }
            };
            action.setChecked(choice == current);
            group.add(action);
        }
        return group;
    }

    private void applyHeapObjectCap(int value) {
        config.maxHeapObjectsRendered = value;
        pipeline.setLimits(ExtractionLimits.defaults().withMaxObjects(value)); // takes effect on the next extraction
        controller.clearHeapCapOverride(); // an explicit choice beats "+N not rendered…"
        controller.refresh(); // render cap applies to the cached snapshot immediately
    }

    private void applyFieldCap(int value) {
        config.maxFieldsPerObjectRendered = value;
        controller.clearFieldCapOverrides();
        controller.refresh();
    }

    private void applyArrayElementCap(int value) {
        config.maxArrayElementsRendered = value;
        controller.clearArrayElementCapOverrides();
        controller.refresh();
    }
}
