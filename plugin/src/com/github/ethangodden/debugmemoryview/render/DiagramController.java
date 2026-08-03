package com.github.ethangodden.debugmemoryview.render;

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
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.draw2d.ConnectionLayer;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.FigureCanvas;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.Layer;
import org.eclipse.draw2d.LayeredPane;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.ScrollPane;
import org.eclipse.draw2d.TextUtilities;
import org.eclipse.draw2d.ToolbarLayout;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.swt.SWT;

import com.github.ethangodden.debugmemoryview.model.MemorySnapshot;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableFrame;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableStruct;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableThread;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.DisplayableVariable;
import com.github.ethangodden.debugmemoryview.model.MemorySnapshot.Value;
import com.github.ethangodden.debugmemoryview.model.diff.ChangeStatus;
import com.github.ethangodden.debugmemoryview.model.diff.DiffEngine;
import com.github.ethangodden.debugmemoryview.model.diff.MemoryDiff;
import com.github.ethangodden.debugmemoryview.render.figures.ColumnFigure;
import com.github.ethangodden.debugmemoryview.render.figures.ContainerFigure;
import com.github.ethangodden.debugmemoryview.render.figures.HeapObjectFigure;
import com.github.ethangodden.debugmemoryview.render.figures.MoreRowFigure;
import com.github.ethangodden.debugmemoryview.render.figures.ObjectPreviewFigure;
import com.github.ethangodden.debugmemoryview.render.figures.VariableRowFigure;
import com.github.ethangodden.debugmemoryview.ui.ViewSettings;

/**
 * Owns the whole Draw2d figure tree of the memory diagram and orchestrates the
 * full rebuild per snapshot (the universal update primitive). One FigureCanvas;
 * a LayeredPane with a "columns" layer (stack | gutter+sash | heap, each column
 * an independent ScrollPane), a mouse-transparent ConnectionLayer on top, and a
 * scroll-thumb overlay layer above that (auto-hiding scrollbars, see
 * {@link ScrollThumbOverlay}).
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
    private final ViewSettings settings;
    private final ColorPalette palette;
    private final FontKit fonts;

    private final LayeredPane rootPane;
    private final Layer columnsLayer;
    private final ColumnsLayout columnsLayout;
    private final ConnectionLayer connectionLayer;
    private final RunningOverlay overlay;
    private final ColumnFigure stackColumn;
    private final ColumnFigure heapColumn;
    private final ScrollPane stackPane;
    private final ScrollPane heapPane;
    private final Figure stackContents;
    private final Figure heapContents;
    private final SashFigure sash;

    private final ScrollThumbOverlay scrollThumbs;

    private final LayoutMemory layoutMemory = new LayoutMemory();
    private final ExpansionMemory expansion = new ExpansionMemory();
    private final HoverController hover;

    private final Map<String, HeapObjectFigure> objectFigures = new HashMap<>();
    private final Map<VariableRowFigure, StateConnection> connectionsBySourceRow = new HashMap<>();
    private final Map<String, DisplayableStruct> byId = new HashMap<>(); // snapshot heap, for previews

    private MemorySnapshot snapshot;
    private MemoryDiff diff;
    private int laneCounter;

    /** A reference row waiting for its arrow (created after all object figures exist). */
    private record PendingRef(VariableRowFigure row, String targetToken, ChangeStatus status, boolean fromStack) {
    }

    public DiagramController(FigureCanvas canvas, ResourceManager resources, ViewSettings settings) {
        this.canvas = canvas;
        this.settings = settings;
        palette = new ColorPalette(resources);
        palette.refresh(canvas, settings.highlightChanges);
        fonts = new FontKit(resources);

        // The root contents fill the viewport while it is wide enough; once the
        // diagram's natural width (see ColumnsLayout) exceeds the viewport the
        // contents overflow and the whole view scrolls horizontally as one unit.
        // Height always fills — per-column vertical scrolling lives inside each
        // ScrollPane. Bars are NEVER (ScrollThumbOverlay paints the affordance);
        // the RangeModels stay live regardless, so wheel/reveal scroll works.
        canvas.setScrollBarVisibility(FigureCanvas.NEVER);
        canvas.getViewport().setContentsTracksWidth(true);
        canvas.getViewport().setContentsTracksHeight(true);

        stackContents = newVerticalContents(8);
        stackPane = new ScrollPane();
        // No stock bars anywhere: ScrollThumbOverlay paints auto-hiding thumbs instead.
        // Scrolling itself (wheel routing, RangeModels, reveal) never touches the bar figures.
        stackPane.setScrollBarVisibility(ScrollPane.NEVER);
        // Contents track the viewport width: a narrowing column SHRINKS each frame
        // to min(natural, available) — headers/identifiers ellipsize, value boxes
        // survive — instead of clipping at the pane edge. Below the figures'
        // box-preserving minimums the contents overflow again, so the horizontal
        // thumb + Shift+wheel stay wired (dormant until that extreme).
        stackPane.getViewport().setContentsTracksWidth(true);
        stackPane.setContents(stackContents);
        stackColumn = new ColumnFigure("Stack", stackPane, palette, fonts);

        heapContents = newVerticalContents(12);
        // Boxes sit flush LEFT; the intra-heap arcs bow on the RIGHT, so reserve
        // their bow width there: same-viewport connections clip to the pane's
        // client area, so the arcs must bow within the contents, not the gutter.
        heapContents.setBorder(new MarginBorder(8, 8, 8, 8 + MemoryConnectionRouter.BOW_MAX));
        heapPane = new ScrollPane();
        heapPane.setScrollBarVisibility(ScrollPane.NEVER);
        heapPane.getViewport().setContentsTracksWidth(true); // same responsive shrink as the stack
        heapPane.setContents(heapContents);
        heapColumn = new ColumnFigure("Heap", heapPane, palette, fonts);

        sash = new SashFigure();

        columnsLayer = new Layer();
        columnsLayout = new ColumnsLayout(stackColumn, sash, heapColumn, stackContents, heapContents);
        columnsLayer.setLayoutManager(columnsLayout);
        columnsLayer.add(stackColumn);
        columnsLayer.add(sash);
        columnsLayer.add(heapColumn);

        connectionLayer = new ConnectionLayer();
        connectionLayer.setEnabled(false); // transparent to mouse events; hover reaches the rows below
        connectionLayer.setAntialias(SWT.ON);
        connectionLayer.setConnectionRouter(
                new MemoryConnectionRouter(this::gutterAbsolute, this::heapArcBaseline));
        connectionLayer.setClippingStrategy(new MemoryClippingStrategy(stackPane, heapPane, this::gutterAbsolute));
        connectionLayer.setMinimumSize(new Dimension(0, 0));
        connectionLayer.setPreferredSize(new Dimension(0, 0));

        overlay = new RunningOverlay();
        overlay.setVisible(false);
        // Like the connection layer: without this, the overlay's current bounds
        // become the root pane's minimum size and the diagram can never shrink.
        overlay.setMinimumSize(new Dimension(0, 0));
        overlay.setPreferredSize(new Dimension(0, 0));

        Layer thumbLayer = new Layer();
        thumbLayer.setEnabled(false); // display-only thumbs; mouse-transparent like the connections
        thumbLayer.setMinimumSize(new Dimension(0, 0));
        thumbLayer.setPreferredSize(new Dimension(0, 0));

        rootPane = new LayeredPane();
        rootPane.add(columnsLayer, "columns");
        rootPane.add(connectionLayer, "connections");
        rootPane.add(thumbLayer, "scrollThumbs");
        rootPane.add(overlay, "overlay");

        scrollThumbs = new ScrollThumbOverlay(canvas, thumbLayer, palette::textForeground);
        // Vertical thumbs are per-column (each pane scrolls its own contents);
        // the single horizontal thumb rides the outer canvas viewport, which is
        // what scrolls the whole diagram sideways.
        scrollThumbs.track(stackPane.getViewport(), true);
        scrollThumbs.track(heapPane.getViewport(), true);
        scrollThumbs.track(canvas.getViewport(), false);

        hover = new HoverController(this);
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
        hover.reset();
        discardFigures();
        stackColumn.header().setText("Stack");
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
            palette.refresh(canvas, settings.highlightChanges);
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
        settings.showStatics = show;
        if (snapshot != null) {
            rebuild();
        }
    }

    // An explicit menu cap supersedes any clicked "+N more…" override, which
    // would otherwise pin the count at MAX_VALUE for the rest of the session.

    public void clearHeapCapOverride() {
        expansion.clearCaps(CAP_KEY_HEAP);
    }

    public void clearFieldCapOverrides() {
        // Every box's field/element/char rows share the "obj:<token>" cap key now
        // (the heap is uniform boxes), so both the field and array-element menu
        // items clear the same overrides.
        expansion.clearCaps("obj:");
    }

    public void clearArrayElementCapOverrides() {
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
        hover.reset();
        Point stackScroll = stackPane.getViewport().getViewLocation().getCopy();
        Point heapScroll = heapPane.getViewport().getViewLocation().getCopy();
        int canvasScrollX = canvas.getViewport().getViewLocation().x;
        canvas.setRedraw(false);
        try {
            palette.refresh(canvas, settings.highlightChanges);
            applyChrome();
            discardFigures();
            if (snapshot == null) {
                return;
            }
            stackColumn.header().setText("Stack — " + threadNameOf(snapshot));
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
            stackPane.getViewport().setViewLocation(stackScroll);
            heapPane.getViewport().setViewLocation(heapScroll);
            canvas.scrollToX(canvasScrollX);
        });
    }

    private void discardFigures() {
        scrollThumbs.reset(); // stale thumb geometry / timers must not outlive the figures
        stackContents.removeAll();
        heapContents.removeAll();
        connectionLayer.removeAll();
        objectFigures.clear();
        connectionsBySourceRow.clear();
        byId.clear();
        laneCounter = 0;
    }

    private void applyChrome() {
        stackColumn.restyle(palette, fonts);
        heapColumn.restyle(palette, fonts);
        sash.setLineColor(palette.boxBorder());
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
            ContainerFigure figure = new ContainerFigure(frame.label(), expanded, palette, fonts, () -> {
                expansion.setFrameCollapsed(frameToken, expanded);
                rebuild();
            });
            if (expanded) {
                populateFrame(figure, frame, refs);
            }
            stackContents.add(figure);
        }
    }

    private void populateFrame(ContainerFigure figure, DisplayableFrame frame, List<PendingRef> refs) {
        String frameToken = frame.id();
        if (frame.note() != null) {
            // Native/obsolete/unreadable frame: a note string stands in for variable rows.
            figure.addRow(infoRow(frame.note()));
            return;
        }
        List<DisplayableVariable> variables = frame.variables(); // this first, then locals
        List<String> rowKeys = MemoryDiff.rowKeys(variables); // same keying as the differ
        renderCapped("frame:" + frameToken, variables.size(), settings.maxLocalsPerFrameRendered, i -> {
            DisplayableVariable variable = variables.get(i);
            ChangeStatus status = palette.effective(diff.statusOf(frameToken, rowKeys.get(i)));
            return newRow(variable, status, refs, true);
        }, figure::addRow);
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
        int heapCap = expansion.capOf(CAP_KEY_HEAP, settings.maxHeapObjectsRendered);
        int shown = Math.min(visible.size(), heapCap);
        Set<String> rendered = new HashSet<>(visible.subList(0, shown));
        int omitted = visible.size() - shown;

        // One vertical column of boxes; ~16 px between OBJECTS (rows inside a box
        // stack with zero spacing — they read as contiguous memory cells).
        Figure heapBody = new Figure();
        ToolbarLayout bodyLayout = new ToolbarLayout(false);
        bodyLayout.setSpacing(16);
        bodyLayout.setStretchMinorAxis(false); // boxes take natural width <= 320
        heapBody.setLayoutManager(bodyLayout);

        for (String token : order) {
            if (!rendered.contains(token)) {
                continue; // statics hidden by the toggle, or elided by the heap cap
            }
            DisplayableStruct struct = byId.get(token);
            if (struct == null) {
                continue;
            }
            heapBody.add(buildObjectFigure(struct, refs));
        }
        if (omitted > 0) {
            heapBody.add(unrenderedBox(omitted));
        }
        heapContents.add(heapBody);
    }

    /** A box is hidden only when it is a statics class and the statics toggle is off. */
    private boolean isVisibleStruct(String token) {
        return settings.showStatics || !token.startsWith(STATICS_TOKEN_PREFIX);
    }

    private HeapObjectFigure buildObjectFigure(DisplayableStruct struct, List<PendingRef> refs) {
        String token = struct.id();
        boolean collapsed = expansion.isObjectCollapsed(token);
        HeapObjectFigure figure = new HeapObjectFigure(struct.type(), collapsed, palette, fonts,
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
        List<DisplayableVariable> fields = struct.variables();
        List<String> rowKeys = MemoryDiff.rowKeys(fields); // same keying as the differ
        renderCapped("obj:" + token, fields.size(), fieldCapFor(token), i -> {
            DisplayableVariable field = fields.get(i);
            ChangeStatus status = palette.effective(diff.statusOf(token, rowKeys.get(i)));
            return newRow(field, status, refs, false);
        }, figure::addRow);
        if (struct.omitted() > 0) {
            figure.addRow(infoRow("(+" + struct.omitted() + " not captured)"));
        }
    }

    /**
     * The default render cap for a struct's rows. Positional fields (arrays / string
     * chars, whose first row label is "0") cap like an array; named fields
     * cap like object fields. Statics structs use the field cap.
     */
    private int fieldCapFor(String token) {
        if (token.startsWith(STATICS_TOKEN_PREFIX)) {
            return settings.maxFieldsPerObjectRendered;
        }
        DisplayableStruct struct = byId.get(token);
        if (struct != null && !struct.variables().isEmpty() && "0".equals(struct.variables().get(0).label())) {
            return settings.maxArrayElementsRendered;
        }
        return settings.maxFieldsPerObjectRendered;
    }

    private IFigure unrenderedBox(int omitted) {
        Figure box = new Figure();
        ToolbarLayout layout = new ToolbarLayout(false);
        layout.setStretchMinorAxis(true);
        box.setLayoutManager(layout);
        box.setOpaque(true);
        box.setBackgroundColor(palette.boxBackground());
        box.setBorder(new org.eclipse.draw2d.LineBorder(palette.boxBorder(), 1));
        box.add(new MoreRowFigure("+ " + omitted + " objects not rendered…", palette, fonts, () -> {
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
            VariableRowFigure row = new VariableRowFigure(null, variable.label(), null, status, palette, fonts);
            hover.hookRow(row);
            return row;
        }

        if (value instanceof Value.Reference ref) {
            Optional<DisplayableStruct> target = snapshot.resolve(ref);
            if (target.isEmpty()) {
                return danglingRow(variable, status);
            }
            String targetToken = target.get().id();
            VariableRowFigure row = new VariableRowFigure(variable.label(), "", targetToken, status,
                    palette, fonts);
            hover.hookRow(row); // reference rows add click/preview/target outline
            refs.add(new PendingRef(row, targetToken, status, fromStack));
            return row;
        }

        // Box value ("null" included): the text fills the cell, no arrow.
        VariableRowFigure row = new VariableRowFigure(variable.label(), boxTextOf(value), null, status,
                palette, fonts);
        hover.hookRow(row); // every row hover-tints
        row.setToolTip(tooltipLabel(typedTooltip(variable.type(), Ellipsis.fullValueText(value))));
        return row;
    }

    /**
     * A dangling reference: the target resolves to no struct. Rendered with a distinct
     * severed-stub glyph in the cell — no arrow (unlike a live reference) and not the
     * "null" text (unlike a null cell) — so all three read differently.
     */
    private VariableRowFigure danglingRow(DisplayableVariable variable, ChangeStatus status) {
        VariableRowFigure row = new VariableRowFigure(variable.label(), "⇥⌀", null, status, palette, fonts);
        hover.hookRow(row);
        row.setToolTip(tooltipLabel(typedTooltip(variable.type(), "dangling reference (no target)")));
        return row;
    }

    /** In-box text: box values verbatim (char-capped), else empty (reference cell). */
    private String boxTextOf(Value value) {
        if (value instanceof Value.BoxValue) {
            return Ellipsis.valueText(value, settings.maxValueChars);
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
        return new MoreRowFigure("+ " + hidden + " more…", palette, fonts, () -> {
            expansion.raiseCap(capKey);
            rebuild();
        });
    }

    private Label infoRow(String text) {
        return MoreRowFigure.mutedRow(text, palette, fonts);
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
            int lane = ref.fromStack() ? laneCounter++ % MemoryConnectionRouter.LANES : 0;
            StateConnection connection = new StateConnection(ref.status(), lane, palette);
            connection.setSourceAnchor(new RowEdgeAnchor(ref.row().valueBox(), Rectangle::getCenter));
            // Cross-pane arrows land on the row's LEFT edge (facing the gutter);
            // same-viewport ones (heap sources) land on its RIGHT edge, matching
            // the router's right-side arcs.
            connection.setTargetAnchor(ref.fromStack()
                    ? new RowEdgeAnchor(target.getReferenceTargetFigure(), Rectangle::getLeft)
                    : new RowEdgeAnchor(target.getReferenceTargetFigure(), Rectangle::getRight));
            connectionsBySourceRow.put(ref.row(), connection);
            connectionLayer.add(connection);
        }
    }

    // ---------------------------------------------------- hover/reveal support

    ColorPalette palette() {
        return palette;
    }

    StateConnection connectionFor(VariableRowFigure row) {
        return connectionsBySourceRow.get(row);
    }

    HeapObjectFigure objectFigureFor(String token) {
        return objectFigures.get(token);
    }

    /** Re-adds the connection so it paints on top of its siblings while hovered. */
    void raiseConnection(StateConnection connection) {
        connectionLayer.add(connection);
    }

    /** Lazy tooltip body for a reference row; null when the target struct is unknown. */
    IFigure buildPreview(VariableRowFigure row) {
        if (row.targetToken() == null) {
            return null;
        }
        DisplayableStruct struct = byId.get(row.targetToken());
        return struct == null ? null : new ObjectPreviewFigure(struct, palette, fonts);
    }

    /** Click-to-reveal: scroll the heap pane to the target and flash its outline. */
    void revealTarget(VariableRowFigure row) {
        if (row.targetToken() == null) {
            return;
        }
        String token = row.targetToken();
        HeapObjectFigure target = objectFigures.get(token);
        if (target == null) {
            return;
        }
        RevealUtil.reveal(heapPane, target); // vertical within the heap pane
        RevealUtil.revealHorizontally(canvas.getViewport(), target); // bring the heap column into the window
        scrollThumbs.show(heapPane.getViewport(), true);
        scrollThumbs.show(canvas.getViewport(), false);
        target.setHoverHighlight(true);
        canvas.getDisplay().timerExec(FLASH_MILLIS, () -> {
            if (canvas.isDisposed()) {
                return;
            }
            // Only un-flash if this figure is still current and not hover-held.
            if (objectFigures.get(token) == target && !hover.isCurrentTarget(target)) {
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
        Rectangle stackBounds = stackColumn.getBounds().getCopy();
        Rectangle heapBounds = heapColumn.getBounds().getCopy();
        stackColumn.translateToAbsolute(stackBounds);
        heapColumn.translateToAbsolute(heapBounds); // sibling of stackColumn: same coordinate space
        return new Rectangle(stackBounds.right(), stackBounds.y,
                Math.max(0, heapBounds.x - stackBounds.right()), stackBounds.height);
    }

    /**
     * Rightmost heap box edge (absolute) intersecting the [topY, bottomY] band —
     * the intra-heap arcs' bow baseline. Boxes align left but their right edges
     * are ragged, so an arc must clear every box it passes, not just its
     * endpoints'. The heap contents hold the heap body, whose children are the
     * boxes (statics boxes at the top, then objects).
     */
    private int heapArcBaseline(int topY, int bottomY) {
        int right = Integer.MIN_VALUE;
        for (IFigure child : heapContents.getChildren()) {
            for (IFigure box : child.getChildren()) {
                Rectangle bounds = box.getBounds().getCopy();
                box.translateToAbsolute(bounds);
                if (bounds.bottom() >= topY && bounds.y <= bottomY) {
                    right = Math.max(right, bounds.right());
                }
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
        return x < gutterAbsolute().getCenter().x ? stackPane : heapPane;
    }

    private static Figure newVerticalContents(int spacing) {
        Figure contents = new Figure();
        ToolbarLayout layout = new ToolbarLayout(false);
        layout.setSpacing(spacing);
        layout.setStretchMinorAxis(false); // frames / heap boxes hug their content width
        contents.setLayoutManager(layout);
        contents.setBorder(new MarginBorder(8));
        return contents;
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
