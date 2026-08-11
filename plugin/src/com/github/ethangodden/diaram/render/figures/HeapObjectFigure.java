package com.github.ethangodden.diaram.render.figures;

import org.eclipse.draw2d.ConnectionAnchor;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.jdt.annotation.Nullable;

import com.github.ethangodden.diaram.render.PluginConfig;

/**
 * One heap object box (also used for arrays): a {@link ContainerFigure} whose
 * rows are field / element / value / STRING-char cells, keyed by object id. On
 * top of the shared container it adds hover highlighting (border recolored to
 * the accent), a width clamp to the heap column, and the reference-target row
 * that inbound arrows land on. Aliasing shows structurally: every reference row
 * with the same target id anchors to the same figure's first body row (see
 * {@link #targetAnchor}).
 */
public class HeapObjectFigure extends ContainerFigure {

    /** Preferred-size floor only; the minimum may go lower so a narrow column can squeeze the box. */
    public static final int MIN_WIDTH = 112;
    public static final int MAX_WIDTH = 320;

    private boolean hoverHighlight;

    public HeapObjectFigure(String title, boolean collapsed,
            PluginConfig config, @Nullable Runnable onToggle) {
        super(title, !collapsed, config, onToggle);
    }

    /**
     * Anchor for an inbound reference arrow, landing on the near edge of the
     * reference-target row: {@code fromLeft} picks the LEFT edge (cross-pane
     * arrows from the stack, facing the gutter) or the RIGHT edge (intra-heap
     * arcs, matching the router's right-side bows).
     */
    public ConnectionAnchor targetAnchor(boolean fromLeft) {
        return new RowEdgeAnchor(referenceTargetFigure(), fromLeft ? Rectangle::getLeft : Rectangle::getRight);
    }

    /**
     * Where reference arrowheads land: the first variable row of the body (first
     * field / array element / STRING char cell / BOXED content row) — a pointer to the start
     * of the allocation. Falls back to the header when there are no such rows
     * (STUB, empty object, user-collapsed box).
     */
    private IFigure referenceTargetFigure() {
        if (body.getParent() == this) {
            for (IFigure child : body.getChildren()) {
                if (child instanceof VariableRowFigure row) {
                    return row;
                }
            }
        }
        return header;
    }

    public void setHoverHighlight(boolean on) {
        if (hoverHighlight == on) {
            return;
        }
        hoverHighlight = on;
        // Same width/style as the base border, recolored — the box never resizes.
        setBorder(borderFor(config, on));
        repaint();
    }

    @Override
    public Dimension getPreferredSize(int wHint, int hHint) {
        Dimension size = super.getPreferredSize(wHint, hHint).getCopy();
        size.width = Math.max(MIN_WIDTH, Math.min(size.width, MAX_WIDTH));
        return size;
    }

    // ToolbarLayout sizes children by max(minWidth, min(clientArea, prefWidth)), so an
    // unclamped minimum (e.g. a long String content label) would override the MAX_WIDTH
    // clamp above and blow the box past the column. No MIN_WIDTH floor here: the real
    // minimum is the rows' box-preserving minimum (header/identifiers at their ellipsis
    // width, value boxes natural), so a narrowing column shrinks the box instead of
    // clipping it at the pane edge.
    @Override
    public Dimension getMinimumSize(int wHint, int hHint) {
        Dimension size = super.getMinimumSize(wHint, hHint).getCopy();
        size.width = Math.min(size.width, MAX_WIDTH);
        return size;
    }
}
