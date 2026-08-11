package com.github.ethangodden.diaram.render;

import java.util.function.Supplier;

import org.eclipse.draw2d.AbstractRouter;
import org.eclipse.draw2d.Connection;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.Viewport;
import org.eclipse.draw2d.ViewportUtilities;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.PointList;
import org.eclipse.draw2d.geometry.Rectangle;

/**
 * Deterministic bezier router. Each edge is a cubic from the source tail dot,
 * then a short fixed horizontal stub into the arrowhead: the curve ends at
 * {@code curveEnd} (STUB outside the target box's near edge), and
 * {@link StateConnection} paints that stub as a straight segment so the head
 * enters the arrowhead horizontally regardless of the curve's shape.
 *
 * Cross-pane (stack -> heap) edges get control points c1=(midX, start.y),
 * c2=(midX, curveEnd.y) with midX in the gutter, varied a few px per connection
 * lane so parallel curves don't coincide; the arrowhead approaches from the
 * LEFT. Intra-heap edges (single heap viewport, statics included) arc on the
 * right side of the column: both control points sit at the rightmost box edge
 * within the arc's vertical band (boxes align left but their right edges are
 * ragged, so clearing only the endpoints' boxes would cross wider boxes in
 * between) plus a bow proportional to the vertical distance (capped at BOW_MAX,
 * the padding the heap contents reserve on the right), keeping nested arcs
 * ordered; the arrowhead approaches from the RIGHT and a self-reference's band
 * is its own box, so it degenerates to a small right hook. The point list is
 * still set — [start, hull extent point, curveEnd, end] — so bounds are right
 * and the stock arrowhead orients along the head stub. The extent
 * point (at midX or the rightmost bow reach) matters: scrolling can move end.x
 * past midX, and without it the painted bulge would escape the damage bounds,
 * leaving stale arc pixels behind. Cross-pane geometry is O(1) per connection
 * per pass; intra-heap adds an O(#boxes) baseline scan.
 */
public class MemoryConnectionRouter extends AbstractRouter {

    public static final int LANES = 5;
    private static final int LANE_SPACING = 6;
    /**
     * Fixed straight run into the arrowhead: the head enters the arrowhead this far outside
     * the target box's near edge, after the curve ends. The tail leaves the source box
     * straight from the curve (no tail stub).
     */
    private static final int STUB = 14;
    private static final int BOW_MIN = 20;
    /** Public: the heap contents reserve this much right padding for the arcs. */
    public static final int BOW_MAX = 80;

    /** Rightmost heap box edge (absolute x) intersecting a vertical band; supplied by the controller. */
    @FunctionalInterface
    public interface ArcBaseline {
        int rightEdgeWithin(int topY, int bottomY);
    }

    private final Supplier<Rectangle> gutterAbsolute;
    private final ArcBaseline heapArcBaseline;

    public MemoryConnectionRouter(Supplier<Rectangle> gutterAbsolute, ArcBaseline heapArcBaseline) {
        this.gutterAbsolute = gutterAbsolute;
        this.heapArcBaseline = heapArcBaseline;
    }

    @Override
    public void route(Connection connection) {
        if (connection.getSourceAnchor() == null || connection.getTargetAnchor() == null) {
            return;
        }
        Point start = getStartPoint(connection).getCopy(); // absolute (tail dot, inside the source box)
        Point end = connection.getTargetAnchor().getLocation(start).getCopy();
        Viewport sourceViewport = viewportOf(connection.getSourceAnchor().getOwner());
        Viewport targetViewport = viewportOf(connection.getTargetAnchor().getOwner());

        // The curve leaves the tail dot directly (no tail stub); only the head has
        // a fixed straight stub into the arrowhead, ending STUB outside the target box.
        Point curveEnd;
        int bulgeX;
        if (sourceViewport != targetViewport) {
            // Stack -> heap: S-curve swinging through the gutter; arrowhead from the LEFT.
            Rectangle gutter = gutterAbsolute.get();
            int lane = connection instanceof StateConnection stateConnection ? stateConnection.laneIndex() : 0;
            bulgeX = gutter.x + gutter.width / 2 + (lane % LANES - LANES / 2) * LANE_SPACING;
            curveEnd = new Point(end.x - STUB, end.y); // target's LEFT edge; stub sits outside it
        } else {
            // Same viewport: bow right of every box in the arc's vertical band
            // (not just the endpoints' — right edges are ragged); a
            // self-reference's band is its own box, leaving a small hook.
            // Arrowhead from the RIGHT.
            curveEnd = new Point(end.x + STUB, end.y); // target's RIGHT edge; stub sits outside it
            int bow = Math.min(BOW_MAX, BOW_MIN + Math.abs(end.y - start.y) / 4);
            int baseline = heapArcBaseline.rightEdgeWithin(Math.min(start.y, end.y), Math.max(start.y, end.y));
            bulgeX = Math.max(baseline, Math.max(start.x, curveEnd.x)) + bow;
        }
        Point c1 = new Point(bulgeX, start.y);
        Point c2 = new Point(bulgeX, curveEnd.y);

        // Point list: the endpoints (tail dot / arrowhead), the head-stub joint, and
        // a hull extent point at the bulge so the bounds cover the curve even when a
        // scroll drops end.x left of the bulge. The head-stub joint keeps the stock
        // arrowhead oriented along the head stub. The curve itself is painted from
        // the control points by StateConnection, not from these straight segments.
        PointList points = new PointList(4);
        points.addPoint(start);
        points.addPoint(new Point(bulgeX, (start.y + curveEnd.y) / 2));
        points.addPoint(curveEnd);
        points.addPoint(end);

        // Identity in this diagram (one shared coordinate system), kept for correctness.
        connection.translateToRelative(c1);
        connection.translateToRelative(c2);
        connection.translateToRelative(curveEnd);
        for (int i = 0; i < points.size(); i++) {
            Point p = points.getPoint(i);
            connection.translateToRelative(p);
            points.setPoint(p, i);
        }
        if (connection instanceof StateConnection stateConnection) {
            stateConnection.setCurve(c1, c2, curveEnd); // before setPoints: it triggers the repaint
        }
        connection.setPoints(points);
    }

    private static Viewport viewportOf(IFigure owner) {
        return owner == null ? null : ViewportUtilities.getNearestEnclosingViewport(owner);
    }
}
