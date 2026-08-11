package com.github.ethangodden.diaram.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.draw2d.ConnectionLayer;
import org.eclipse.draw2d.Cursors;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.FigureCanvas;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.Layer;
import org.eclipse.draw2d.LayeredPane;
import org.eclipse.draw2d.MouseEvent;
import org.eclipse.draw2d.MouseListener;
import org.eclipse.draw2d.MouseMotionListener;
import org.eclipse.draw2d.SWTEventDispatcher;
import org.eclipse.draw2d.ScrollPane;
import org.eclipse.draw2d.TextUtilities;
import org.eclipse.draw2d.ToolTipHelper;
import org.eclipse.draw2d.ToolbarLayout;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;

import com.github.ethangodden.diaram.model.MemorySnapshot;
import com.github.ethangodden.diaram.model.MemorySnapshot.DisplayableFrame;
import com.github.ethangodden.diaram.model.MemorySnapshot.DisplayableStruct;
import com.github.ethangodden.diaram.model.MemorySnapshot.DisplayableThread;
import com.github.ethangodden.diaram.model.MemorySnapshot.DisplayableVariable;
import com.github.ethangodden.diaram.model.MemorySnapshot.Value;
import com.github.ethangodden.diaram.model.diff.ChangeStatus;
import com.github.ethangodden.diaram.model.diff.DiffEngine;
import com.github.ethangodden.diaram.model.diff.MemoryDiff;
import com.github.ethangodden.diaram.render.figures.ContainerFigure;
import com.github.ethangodden.diaram.render.figures.HeapObjectFigure;
import com.github.ethangodden.diaram.render.figures.MoreRowFigure;
import com.github.ethangodden.diaram.render.figures.ObjectPreviewFigure;
import com.github.ethangodden.diaram.render.figures.VariableRowFigure;

/**
 * Owns the whole Draw2d figure tree of the memory diagram and orchestrates the
 * full rebuild per snapshot (the universal update primitive). One FigureCanvas;
 * a LayeredPane with a "columns" layer (stack | gutter | heap, each column an
 * independent ScrollPane; the layer itself paints the fixed divider line
 * centered in the gutter, see {@link ColumnsLayer}), a mouse-transparent
 * ConnectionLayer on top, and a scroll-thumb overlay layer above that
 * (auto-hiding scrollbars, see {@link ScrollThumbOverlay}).
 * Connections re-route on scroll purely via the stock anchor mechanism: the
 * pane contents figure physically moves, figureMoved fires up the tree, and
 * every AbstractConnectionAnchor re-fires — no manual re-anchoring anywhere.
 *
 * <p>Consumes the neutral {@link MemorySnapshot} + {@link MemoryDiff}: the stack
 * is {@link DisplayableFrame}s (variable rows or a note string), the heap is uniform
 * {@link DisplayableStruct}s (a type header plus {@link DisplayableVariable} rows; statics
 * classes are ordinary structs emitted first). Presentation is inferred from neutral signals
 * only — an unexplored struct is "(not explored)", omitted drives the
 * "+N not captured" row, a row's {@link Value} decides its cell/arrow.
 *
 * <p>All methods must be called on the SWT UI thread.
 */
public class DiagramController {

    private static final String CAP_KEY_HEAP = "heap";
    private static final String STATICS_TOKEN_PREFIX = "statics:";
    private static final int WHEEL_STEP = 16;
    private static final int FLASH_MILLIS = 900;

    private final FigureCanvas canvas;
    private final PluginConfig config;
    private final ColorPalette palette;
    private final FontKit fonts;

    private final LayeredPane rootPane;
    private final ColumnsLayer columnsLayer;
    private final ColumnsLayout columnsLayout;
    private final ConnectionLayer connectionLayer;
    private final RunningOverlay overlay;
    private final DiagramColumn stack;
    private final DiagramColumn heap;

    private final ScrollThumbOverlay scrollThumbs;

    private final LayoutMemory layoutMemory = new LayoutMemory();
    private final ExpansionMemory expansion = new ExpansionMemory();

    private final Map<String, HeapObjectFigure> objectFigures = new HashMap<>();
    private final Map<VariableRowFigure, StateConnection> connectionsBySourceRow = new HashMap<>();
    private final Map<String, DisplayableStruct> byId = new HashMap<>(); // snapshot heap, for previews

    private MemorySnapshot snapshot;
    private MemoryDiff diff;
    private int laneCounter;
    private @Nullable Figure heapBody; // the heap's vertical box list; heapArcBaseline scans its children

    /** A reference row waiting for its arrow (created after all object figures exist). */
    private record PendingRef(VariableRowFigure row, String targetToken, ChangeStatus status, boolean fromStack) {
    }

    public DiagramController(FigureCanvas canvas, ResourceManager resources, PluginConfig config) {
        this.canvas = canvas;
        this.config = config;
        config.initRenderResources(resources);
        palette = config.palette();
        fonts = config.fonts();
        palette.refresh(canvas, config.highlightChanges);

        // The root contents fill the viewport while it is wide enough; once the
        // diagram's natural width (see ColumnsLayout) exceeds the viewport the
        // contents overflow and the whole view scrolls horizontally as one unit.
        // Height always fills — per-column vertical scrolling lives inside each
        // ScrollPane. Bars are NEVER (ScrollThumbOverlay paints the affordance);
        // the RangeModels stay live regardless, so wheel/reveal scroll works.
        canvas.setScrollBarVisibility(FigureCanvas.NEVER);
        canvas.getViewport().setContentsTracksWidth(true);
        canvas.getViewport().setContentsTracksHeight(true);

        // Draw2d's stock ToolTipHelper force-hides a visible tooltip 5 s after it
        // appears, even with the pointer still on the row — far too short to read
        // a hover preview. Stretch the delay to effectively-never; the tip still
        // hides normally the moment the pointer leaves the row.
        canvas.getLightweightSystem().setEventDispatcher(new SWTEventDispatcher() {
            @Override
            protected ToolTipHelper createToolTipHelper() {
                ToolTipHelper helper = super.createToolTipHelper();
                helper.setHideDelay(Integer.MAX_VALUE);
                return helper;
            }
        });

        stack = new DiagramColumn("Stack", 8, 8, config);

        // Heap boxes sit flush LEFT; the intra-heap arcs bow on the RIGHT, so
        // reserve their bow width there: same-viewport connections clip to the
        // pane's client area, so the arcs must bow within the contents, not the
        // gutter.
        heap = new DiagramColumn("Heap", 12, 8 + StateConnection.Router.BOW_MAX, config);

        columnsLayer = new ColumnsLayer();
        columnsLayout = new ColumnsLayout(stack, heap, stack.contents(), heap.contents());
        columnsLayer.setLayoutManager(columnsLayout);
        columnsLayer.add(stack);
        columnsLayer.add(heap);

        connectionLayer = new ConnectionLayer();
        connectionLayer.setEnabled(false); // transparent to mouse events; hover reaches the rows below
        connectionLayer.setAntialias(SWT.ON);
        connectionLayer.setConnectionRouter(
                new StateConnection.Router(this::gutterAbsolute, this::heapArcBaseline));
        connectionLayer.setClippingStrategy(
                new StateConnection.Clipping(stack.pane(), heap.pane(), this::gutterAbsolute));
        connectionLayer.setMinimumSize(new Dimension(0, 0));
        connectionLayer.setPreferredSize(new Dimension(0, 0));

        overlay = new RunningOverlay();
        overlay.setVisible(false);
        // Like the connection layer: without this, the overlay's current bounds
        // become the root pane's minimum size and the diagram can never shrink.
        overlay.setMinimumSize(new Dimension(0, 0));
        overlay.setPreferredSize(new Dimension(0, 0));

        scrollThumbs = new ScrollThumbOverlay(canvas, palette::textForeground);

        rootPane = new LayeredPane();
        rootPane.add(columnsLayer, "columns");
        rootPane.add(connectionLayer, "connections");
        rootPane.add(scrollThumbs.layer(), "scrollThumbs");
        rootPane.add(overlay, "overlay");
        // Vertical thumbs are per-column (each pane scrolls its own contents);
        // the single horizontal thumb rides the outer canvas viewport, which is
        // what scrolls the whole diagram sideways.
        scrollThumbs.track(stack.pane().getViewport(), true);
        scrollThumbs.track(heap.pane().getViewport(), true);
        scrollThumbs.track(canvas.getViewport(), false);

        applyChrome();
    }

    /** The LayeredPane; the view sets it as the canvas contents. */
    public IFigure getRootFigure() {
        return rootPane;
    }

    /** Full rebuild; caches (snapshot, diff) so refresh()/toggles can re-render. */
    public void setSnapshot(MemorySnapshot newSnapshot, @Nullable MemoryDiff newDiff) {
        snapshot = newSnapshot;
        // No diff supplied: every variable is at a fresh address, i.e. an initial (all-NEW) diff.
        diff = newDiff != null ? newDiff : DiffEngine.diff(null, newSnapshot);
        rebuild();
    }

    /** Gray-out overlay without discarding figures (thread resumed). */
    public void setRunning(boolean running) {
        overlay.setVisible(running);
    }

    /** Empties the diagram and drops the cached diagram. */
    public void clear() {
        snapshot = null;
        diff = null;
        resetHover();
        discardFigures();
        stack.setHeaderText("Stack");
        canvas.redraw();
    }

    /** clear() plus session-scoped memories (layout slots, expansion state). */
    public void clearSession() {
        clear();
        layoutMemory.clear();
        expansion.clear();
    }

    /** Re-renders the cached diagram (theme switch / preference change pickup). */
    public void refresh() {
        if (snapshot != null) {
            rebuild();
        } else {
            palette.refresh(canvas, config.highlightChanges);
            applyChrome();
            canvas.redraw();
        }
    }

    public void expandAll() {
        if (snapshot == null) {
            return;
        }
        expansion.expandAll();
        rebuild();
    }

    public void collapseAll() {
        if (snapshot == null) {
            return;
        }
        for (DisplayableFrame frame : framesOf(snapshot)) {
            expansion.setFrameCollapsed(frame.id(), true);
        }
        for (DisplayableStruct struct : snapshot.heap()) {
            expansion.setObjectCollapsed(struct.id(), true);
        }
        rebuild();
    }

    public void setShowStatics(boolean show) {
        config.showStatics = show;
        if (snapshot != null) {
            rebuild();
        }
    }

    // An explicit menu cap supersedes any clicked "+N more…" override, which
    // would otherwise pin the count at MAX_VALUE for the rest of the session.

    public void clearHeapCapOverride() {
        expansion.clearCaps(CAP_KEY_HEAP);
    }

    /**
     * Every box's field/element/char rows share the "obj:&lt;token&gt;" cap key
     * (the heap is uniform boxes), so the field and array-element menu items
     * both clear the same overrides.
     */
    public void clearObjectCapOverrides() {
        expansion.clearCaps("obj:");
    }

    /**
     * Routed from the canvas SWT wheel listener. Draw2d dispatches wheel events to
     * the focus figure, so we drive scrolling ourselves. Shift = horizontal, which
     * is a whole-view gesture: the entire diagram scrolls sideways under the
     * viewport (the columns keep their natural width; see ColumnsLayout). A plain
     * wheel scrolls the column under the pointer vertically.
     */
    public void handleWheel(org.eclipse.swt.events.MouseEvent event) {
        int delta = -event.count * WHEEL_STEP;
        if ((event.stateMask & SWT.SHIFT) != 0) {
            scrollCanvasHorizontally(delta);
            return;
        }
        ScrollPane pane = paneAt(event.x, event.y);
        if (pane == null) {
            return;
        }
        pane.scrollVerticalTo(pane.getViewport().getVerticalRangeModel().getValue() + delta);
        scrollThumbs.show(pane.getViewport(), true);
    }

    /**
     * Native horizontal wheel (a trackpad two-finger swipe on macOS) arrives as a
     * separate SWT event that the MouseWheelListener never sees, so the view wires
     * it here: same whole-view sideways scroll as Shift+wheel.
     */
    public void handleHorizontalWheel(int count) {
        scrollCanvasHorizontally(-count * WHEEL_STEP);
    }

    private void scrollCanvasHorizontally(int delta) {
        // FigureCanvas.scrollToX clamps to the horizontal RangeModel.
        canvas.scrollToX(canvas.getViewport().getHorizontalRangeModel().getValue() + delta);
        // A clamped wheel changes no RangeModel value (so no listener fires) but
        // should still flash the thumb — the user is actively scrolling.
        scrollThumbs.show(canvas.getViewport(), false);
    }

    // ---------------------------------------------------------------- rebuild

    private void rebuild() {
        resetHover();
        Point stackScroll = stack.saveScroll();
        Point heapScroll = heap.saveScroll();
        int canvasScrollX = canvas.getViewport().getViewLocation().x;
        canvas.setRedraw(false);
        try {
            palette.refresh(canvas, config.highlightChanges);
            applyChrome();
            discardFigures();
            if (snapshot == null) {
                return;
            }
            stack.setHeaderText("Stack — " + threadNameOf(snapshot));
            List<PendingRef> refs = new ArrayList<>();
            buildHeap(refs); // first: object figures must exist before arrows and stack tooltips
            buildStack(refs);
            createConnections(refs);
        } finally {
            canvas.setRedraw(true);
        }
        // A rebuild can change the diagram's natural (minimum) width, and the
        // enclosing viewport derives its horizontal scroll range from that. Make
        // the relayout explicit instead of leaning on child-add revalidation
        // bubbling to the root, so the scroll range always tracks the new contents.
        columnsLayer.revalidate();
        // Restore scroll positions once layout is valid; RangeModel clamps shrunken content.
        canvas.getDisplay().asyncExec(() -> {
            if (canvas.isDisposed()) {
                return;
            }
            stack.restoreScroll(stackScroll);
            heap.restoreScroll(heapScroll);
            canvas.scrollToX(canvasScrollX);
        });
    }

    private void discardFigures() {
        scrollThumbs.reset(); // stale thumb geometry / timers must not outlive the figures
        stack.discard();
        heap.discard();
        heapBody = null;
        connectionLayer.removeAll();
        objectFigures.clear();
        connectionsBySourceRow.clear();
        byId.clear();
        laneCounter = 0;
    }

    private void applyChrome() {
        stack.restyle(config);
        heap.restyle(config);
        columnsLayer.setLineColor(palette.boxBorder());
    }

    // ------------------------------------------------------------------ stack

    private void buildStack(List<PendingRef> refs) {
        // The snapshot carries frames top-of-stack first; render bottom-of-stack
        // first so the stack grows DOWNWARD, as real memory does (the newest,
        // top-of-stack frame lands at the BOTTOM of the column).
        List<DisplayableFrame> live = framesOf(snapshot);
        for (int i = live.size() - 1; i >= 0; i--) {
            DisplayableFrame frame = live.get(i);
            String frameToken = frame.id();
            // Every frame builds its rows eagerly; only user-collapsed frames stay shut.
            boolean expanded = !expansion.isFrameCollapsed(frameToken);
            ContainerFigure figure = new ContainerFigure(frame.label(), expanded, config, () -> {
                expansion.setFrameCollapsed(frameToken, expanded);
                rebuild();
            });
            if (expanded) {
                populateFrame(figure, frame, refs);
            }
            stack.addContent(figure);
        }
    }

    private void populateFrame(ContainerFigure figure, DisplayableFrame frame, List<PendingRef> refs) {
        if (frame.note() != null) {
            // Native/obsolete/unreadable frame: a note string stands in for variable rows.
            figure.addRow(infoRow(frame.note()));
            return;
        }
        // frame.variables() is this first, then locals.
        addVariableRows(figure, frame.id(), frame.variables(), "frame:" + frame.id(),
                config.maxLocalsPerFrameRendered, true, refs);
    }

    // ------------------------------------------------------------------- heap

    private void buildHeap(List<PendingRef> refs) {
        for (DisplayableStruct struct : snapshot.heap()) {
            byId.put(struct.id(), struct);
        }

        List<String> order = HeapLayouter.assign(snapshot, layoutMemory);

        // Heap cap chosen in discovery order: roots-first survival. Only the
        // visible (statics filtered) structs count toward the cap.
        List<String> visible = new ArrayList<>();
        for (DisplayableStruct struct : snapshot.heap()) {
            if (isVisibleStruct(struct.id())) {
                visible.add(struct.id());
            }
        }
        int heapCap = expansion.capOf(CAP_KEY_HEAP, config.maxHeapObjectsRendered);
        int shown = Math.min(visible.size(), heapCap);
        Set<String> rendered = new HashSet<>(visible.subList(0, shown));
        int omitted = visible.size() - shown;

        // One vertical column of boxes; ~16 px between OBJECTS (rows inside a box
        // stack with zero spacing — they read as contiguous memory cells).
        Figure body = new Figure();
        ToolbarLayout bodyLayout = new ToolbarLayout(false);
        bodyLayout.setSpacing(16);
        bodyLayout.setStretchMinorAxis(false); // boxes take natural width <= 320
        body.setLayoutManager(bodyLayout);

        for (String token : order) {
            if (!rendered.contains(token)) {
                continue; // statics hidden by the toggle, or elided by the heap cap
            }
            DisplayableStruct struct = byId.get(token);
            if (struct == null) {
                continue;
            }
            body.add(buildObjectFigure(struct, refs));
        }
        if (omitted > 0) {
            body.add(unrenderedBox(omitted));
        }
        heapBody = body;
        heap.addContent(body);
    }

    /** A box is hidden only when it is a statics class and the statics toggle is off. */
    private boolean isVisibleStruct(String token) {
        return config.showStatics || !token.startsWith(STATICS_TOKEN_PREFIX);
    }

    private HeapObjectFigure buildObjectFigure(DisplayableStruct struct, List<PendingRef> refs) {
        String token = struct.id();
        boolean collapsed = expansion.isObjectCollapsed(token);
        HeapObjectFigure figure = new HeapObjectFigure(struct.type(), collapsed, config,
                () -> {
                    expansion.setObjectCollapsed(token, !collapsed);
                    rebuild();
                });
        if (!collapsed) {
            populateObject(figure, struct, refs);
        }
        objectFigures.put(token, figure); // aliasing: same token -> same figure instance
        return figure;
    }

    /**
     * Uniform struct body: a single muted "(not explored)" row for an unexplored
     * struct, otherwise one row per {@link DisplayableVariable} ("label : [value box]"),
     * a "+N more…" expander when the render cap bites, and a "+N not captured"
     * row for the fields dropped at extraction ({@code omitted}). Strings,
     * arrays, boxed values and enums all arrive as ordinary fields, so no
     * per-kind special-casing is needed.
     */
    private void populateObject(HeapObjectFigure figure, DisplayableStruct struct, List<PendingRef> refs) {
        if (!struct.explored()) {
            figure.addRow(infoRow("(not explored)"));
            return;
        }
        String token = struct.id();
        addVariableRows(figure, token, struct.variables(), "obj:" + token, fieldCapFor(token), false, refs);
        if (struct.omitted() > 0) {
            figure.addRow(infoRow("(+" + struct.omitted() + " not captured)"));
        }
    }

    /**
     * The shared row-building recipe for frames and heap boxes: diff-colored
     * variable rows ({@code containerId} keys the diff address, with the same
     * row keying as the differ), capped with a "+N more…" expander under
     * {@code capKey}.
     */
    private void addVariableRows(ContainerFigure figure, String containerId, List<DisplayableVariable> variables,
            String capKey, int defaultCap, boolean fromStack, List<PendingRef> refs) {
        List<String> rowKeys = MemoryDiff.rowKeys(variables);
        renderCapped(capKey, variables.size(), defaultCap, i -> {
            DisplayableVariable variable = variables.get(i);
            ChangeStatus status = palette.effective(diff.statusOf(containerId, rowKeys.get(i)));
            return newRow(variable, status, refs, fromStack);
        }, figure::addRow);
    }

    /**
     * The default render cap for a struct's rows. Positional fields (arrays / string
     * chars, whose first row label is "0") cap like an array; named fields
     * cap like object fields. Statics structs use the field cap.
     */
    private int fieldCapFor(String token) {
        if (token.startsWith(STATICS_TOKEN_PREFIX)) {
            return config.maxFieldsPerObjectRendered;
        }
        DisplayableStruct struct = byId.get(token);
        if (struct != null && !struct.variables().isEmpty() && "0".equals(struct.variables().get(0).label())) {
            return config.maxArrayElementsRendered;
        }
        return config.maxFieldsPerObjectRendered;
    }

    private IFigure unrenderedBox(int omitted) {
        Figure box = new Figure();
        ToolbarLayout layout = new ToolbarLayout(false);
        layout.setStretchMinorAxis(true);
        box.setLayoutManager(layout);
        box.setOpaque(true);
        box.setBackgroundColor(palette.boxBackground());
        box.setBorder(new org.eclipse.draw2d.LineBorder(palette.boxBorder(), 1));
        box.add(new MoreRowFigure("+ " + omitted + " objects not rendered…", config, () -> {
            expansion.raiseCap(CAP_KEY_HEAP);
            rebuild();
        }));
        return box;
    }

    // ------------------------------------------------------------------- rows

    /**
     * "label : &lt;box&gt;" — no type text (types live in the heap struct headers).
     * The box holds the value text ("null" for the debuggee's null); it is empty
     * for normal references (the arrow tail sits inside it), and shows a distinct
     * dangling marker for a reference that resolves to no struct. A box-only field
     * (the enum constant marker: no declared type) drops the label and shows the
     * label text inside the box. The declared type moves into the tooltip.
     */
    private VariableRowFigure newRow(DisplayableVariable variable, ChangeStatus status, List<PendingRef> refs,
            boolean fromStack) {
        Value value = variable.value();

        // Box-only content row: the enum constant marker arrives as a leading field
        // with no declared type. Its label is the content shown in the box (no label,
        // no arrow), mirroring the old enum-constant/boxed row.
        if (variable.type() == null) {
            VariableRowFigure row = new VariableRowFigure(null, variable.label(), null, status, config);
            hookRow(row);
            return row;
        }

        if (value instanceof Value.Reference ref) {
            Optional<DisplayableStruct> target = snapshot.resolve(ref);
            if (target.isEmpty()) {
                return danglingRow(variable, status);
            }
            String targetToken = target.get().id();
            VariableRowFigure row = new VariableRowFigure(variable.label(), "", targetToken, status,
                    config);
            hookRow(row); // reference rows add click/preview/target outline
            refs.add(new PendingRef(row, targetToken, status, fromStack));
            return row;
        }

        // Box value ("null" included): the text fills the cell, no arrow.
        VariableRowFigure row = new VariableRowFigure(variable.label(), boxTextOf(value), null, status,
                config);
        hookRow(row); // every row hover-tints
        row.setToolTip(tooltipLabel(typedTooltip(variable.type(), Ellipsis.fullValueText(value))));
        return row;
    }

    /**
     * A dangling reference: the target resolves to no struct. Rendered with a distinct
     * severed-stub glyph in the cell — no arrow (unlike a live reference) and not the
     * "null" text (unlike a null cell) — so all three read differently.
     */
    private VariableRowFigure danglingRow(DisplayableVariable variable, ChangeStatus status) {
        VariableRowFigure row = new VariableRowFigure(variable.label(), "⇥⌀", null, status, config);
        hookRow(row);
        row.setToolTip(tooltipLabel(typedTooltip(variable.type(), "dangling reference (no target)")));
        return row;
    }

    /** In-box text: box values verbatim (char-capped), else empty (reference cell). */
    private String boxTextOf(Value value) {
        if (value instanceof Value.BoxValue) {
            return Ellipsis.valueText(value, config.maxValueChars);
        }
        return ""; // Reference: an empty cell (the arrow tail sits inside it)
    }

    private static String typedTooltip(String declaredTypeName, String fullValue) {
        return declaredTypeName == null ? fullValue : declaredTypeName + " : " + fullValue;
    }

    /**
     * Renders up to {@code cap(capKey)} of {@code total} rows — {@code rowFor.apply(i)} builds each
     * and {@code addRow} appends it — then a "+N more…" expander when the list is capped.
     */
    private void renderCapped(String capKey, int total, int defaultMax,
            IntFunction<IFigure> rowFor, Consumer<IFigure> addRow) {
        int shown = Math.min(total, expansion.capOf(capKey, defaultMax));
        for (int i = 0; i < shown; i++) {
            addRow.accept(rowFor.apply(i));
        }
        if (shown < total) {
            addRow.accept(moreRow(total - shown, capKey));
        }
    }

    private MoreRowFigure moreRow(int hidden, String capKey) {
        return new MoreRowFigure("+ " + hidden + " more…", config, () -> {
            expansion.raiseCap(capKey);
            rebuild();
        });
    }

    private Label infoRow(String text) {
        return MoreRowFigure.mutedRow(text, config);
    }

    private Label tooltipLabel(String text) {
        Label tip = new Label(" " + StringUtils.abbreviate(text, Ellipsis.ELLIPSIS, 300 + 1) + " ");
        tip.setFont(fonts.value());
        return tip;
    }

    // ------------------------------------------------------------ connections

    private void createConnections(List<PendingRef> refs) {
        for (PendingRef ref : refs) {
            HeapObjectFigure target = objectFigures.get(ref.targetToken());
            if (target == null) {
                // Target elided by the heap cap: no arrow, explain on the row instead.
                ref.row().setToolTip(
                        tooltipLabel("Target " + ref.targetToken() + " not shown — raise the heap object cap"));
                continue;
            }
            // Round-robin lanes for cross-pane edges, assigned in build order (bottom
            // of stack first), so parallel curves spread across the gutter.
            int lane = ref.fromStack() ? laneCounter++ % StateConnection.Router.LANES : 0;
            StateConnection connection = new StateConnection(ref.status(), lane, config);
            connection.setSourceAnchor(ref.row().sourceAnchor());
            // Cross-pane arrows land on the target row's LEFT edge (facing the
            // gutter); same-viewport ones (heap sources) land on its RIGHT edge,
            // matching the router's right-side arcs.
            connection.setTargetAnchor(target.targetAnchor(ref.fromStack()));
            connectionsBySourceRow.put(ref.row(), connection);
            connectionLayer.add(connection);
        }
    }

    // ------------------------------------------------------------ hover/reveal

    // Single-slot hover state for variable rows: every row (primitive, null,
    // unreadable) gets the blue row tint; reference rows additionally get
    // connection thicken/recolor + target box outline + lazy preview tooltip,
    // and click reveals the target in the heap pane. Draw2d guarantees
    // mouseExited(old) before mouseEntered(new), so one slot suffices.
    // resetHover() runs before every rebuild; stale exits are ignored by
    // identity check.
    private VariableRowFigure hoveredRow;
    private StateConnection hoveredConnection;
    private HeapObjectFigure hoveredTarget;

    // One shared listener pair for every row; the row is the event source.
    private final MouseMotionListener rowHoverListener = new MouseMotionListener.Stub() {
        @Override
        public void mouseEntered(MouseEvent me) {
            if (me.getSource() instanceof VariableRowFigure row) {
                hoverEnter(row);
            }
        }

        @Override
        public void mouseExited(MouseEvent me) {
            if (me.getSource() instanceof VariableRowFigure row && hoveredRow == row) {
                resetHover(); // a mismatch is a stale exit after a rebuild
            }
        }
    };

    private final MouseListener rowClickListener = new MouseListener.Stub() {
        @Override
        public void mousePressed(MouseEvent me) {
            if (me.button == 1 && me.getSource() instanceof VariableRowFigure row) {
                me.consume();
                revealTarget(row);
            }
        }
    };

    /**
     * Registers hover behavior on any variable row; reference rows
     * (targetToken != null) additionally get click-to-reveal and the hand cursor.
     */
    private void hookRow(VariableRowFigure row) {
        row.addMouseMotionListener(rowHoverListener);
        if (row.targetToken() != null) {
            row.addMouseListener(rowClickListener);
            row.setCursor(Cursors.HAND);
        }
    }

    private void hoverEnter(VariableRowFigure row) {
        if (hoveredRow == row) {
            return;
        }
        resetHover();
        hoveredRow = row;
        row.setHoverHighlight(true, config);

        hoveredConnection = connectionsBySourceRow.get(row);
        if (hoveredConnection != null) {
            hoveredConnection.setHover(true, config);
            connectionLayer.add(hoveredConnection); // re-add: paint over sibling arrows
        }
        if (row.targetToken() != null) {
            hoveredTarget = objectFigures.get(row.targetToken());
            if (hoveredTarget != null) {
                hoveredTarget.setHoverHighlight(true);
            }
        }
        if (row.getToolTip() == null) {
            // Lazy preview tooltip, built once from the cached snapshot heap.
            DisplayableStruct struct = byId.get(row.targetToken());
            if (struct != null) {
                row.setToolTip(new ObjectPreviewFigure(struct, config)); // ToolTipHelper shows it in place
            }
        }
    }

    /** Clears the hover slot and restores visuals; safe on figures about to be discarded. */
    private void resetHover() {
        if (hoveredRow != null) {
            hoveredRow.setHoverHighlight(false, config);
        }
        if (hoveredConnection != null) {
            hoveredConnection.setHover(false, config);
        }
        if (hoveredTarget != null) {
            hoveredTarget.setHoverHighlight(false);
        }
        hoveredRow = null;
        hoveredConnection = null;
        hoveredTarget = null;
    }

    /** Click-to-reveal: scroll the heap pane to the target and flash its outline. */
    private void revealTarget(VariableRowFigure row) {
        if (row.targetToken() == null) {
            return;
        }
        String token = row.targetToken();
        HeapObjectFigure target = objectFigures.get(token);
        if (target == null) {
            return;
        }
        RevealUtil.reveal(heap.pane(), target); // vertical within the heap pane
        RevealUtil.revealHorizontally(canvas.getViewport(), target); // bring the heap column into the window
        scrollThumbs.show(heap.pane().getViewport(), true);
        scrollThumbs.show(canvas.getViewport(), false);
        target.setHoverHighlight(true);
        canvas.getDisplay().timerExec(FLASH_MILLIS, () -> {
            if (canvas.isDisposed()) {
                return;
            }
            // Only un-flash if this figure is still current and not hover-held.
            if (objectFigures.get(token) == target && hoveredTarget != target) {
                target.setHoverHighlight(false);
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private static List<DisplayableFrame> framesOf(MemorySnapshot snapshot) {
        return snapshot.renderedThread().map(DisplayableThread::frames).orElse(List.of());
    }

    private static String threadNameOf(MemorySnapshot snapshot) {
        return snapshot.renderedThread().map(DisplayableThread::name).orElse("?");
    }

    private Rectangle gutterAbsolute() {
        Rectangle stackBounds = stack.getBounds().getCopy();
        Rectangle heapBounds = heap.getBounds().getCopy();
        stack.translateToAbsolute(stackBounds);
        heap.translateToAbsolute(heapBounds); // sibling of stack column: same coordinate space
        return new Rectangle(stackBounds.right(), stackBounds.y,
                Math.max(0, heapBounds.x - stackBounds.right()), stackBounds.height);
    }

    /**
     * Rightmost heap box edge (absolute) intersecting the [topY, bottomY] band —
     * the intra-heap arcs' bow baseline. Boxes align left but their right edges
     * are ragged, so an arc must clear every box it passes, not just its
     * endpoints'. The heapBody's children are the boxes (statics boxes at the
     * top, then objects).
     */
    private int heapArcBaseline(int topY, int bottomY) {
        int right = Integer.MIN_VALUE;
        if (heapBody == null) {
            return right;
        }
        for (IFigure box : heapBody.getChildren()) {
            Rectangle bounds = box.getBounds().getCopy();
            box.translateToAbsolute(bounds);
            if (bounds.bottom() >= topY && bounds.y <= bottomY) {
                right = Math.max(right, bounds.right());
            }
        }
        return right;
    }

    private ScrollPane paneAt(int x, int y) {
        IFigure contents = canvas.getContents();
        if (contents != null) {
            IFigure hit = contents.findFigureAt(x, y);
            for (IFigure figure = hit; figure != null; figure = figure.getParent()) {
                if (figure instanceof ScrollPane scrollPane) {
                    return scrollPane;
                }
            }
        }
        return x < gutterAbsolute().getCenter().x ? stack.pane() : heap.pane();
    }

    /**
     * The "columns" layer: parent of the stack and heap columns, and also the
     * painter of the fixed divider line centered between them. The columns are
     * content-driven (see {@link ColumnsLayout}), so there is no ratio to drag —
     * the line is a plain visual marker of the stack/heap boundary, positioned
     * from the columns' current bounds rather than tracked as its own figure.
     */
    private final class ColumnsLayer extends Layer {

        private static final int DIVIDER_WIDTH = 6;

        private @Nullable Color lineColor;

        void setLineColor(@Nullable Color lineColor) {
            this.lineColor = lineColor;
            repaint();
        }

        @Override
        protected void paintFigure(Graphics graphics) {
            if (lineColor == null) {
                return;
            }
            Rectangle stackBounds = stack.getBounds();
            Rectangle heapBounds = heap.getBounds();
            int centerX = (stackBounds.right() + heapBounds.x) / 2;
            graphics.setBackgroundColor(lineColor);
            graphics.fillRectangle(centerX - DIVIDER_WIDTH / 2, stackBounds.y, DIVIDER_WIDTH, stackBounds.height);
        }
    }

    /** Translucent veil + centered "Running…" label; toggled by setRunning. */
    private final class RunningOverlay extends Layer {

        RunningOverlay() {
            setEnabled(false);
        }

        @Override
        protected void paintFigure(Graphics graphics) {
            Rectangle bounds = getBounds();
            graphics.setAlpha(150);
            graphics.setBackgroundColor(palette.columnBackground());
            graphics.fillRectangle(bounds);
            graphics.setAlpha(230);
            graphics.setFont(fonts.header());
            graphics.setForegroundColor(palette.textForeground());
            String text = "Running…";
            Dimension extent = TextUtilities.INSTANCE.getStringExtents(text, fonts.header());
            graphics.drawString(text, bounds.x + (bounds.width - extent.width) / 2,
                    bounds.y + (bounds.height - extent.height) / 2);
        }
    }
}
