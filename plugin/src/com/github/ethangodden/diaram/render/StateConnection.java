package com.github.ethangodden.diaram.render;

import java.util.function.Supplier;

import org.eclipse.draw2d.AbstractRouter;
import org.eclipse.draw2d.Connection;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.IClippingStrategy;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.PolygonDecoration;
import org.eclipse.draw2d.PolylineConnection;
import org.eclipse.draw2d.ScrollPane;
import org.eclipse.draw2d.Viewport;
import org.eclipse.draw2d.ViewportUtilities;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.PointList;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.widgets.Display;

import com.github.ethangodden.diaram.model.diff.ChangeStatus;

/**
 * A reference arrow, colored by the SOURCE row's change status, with a filled
 * tail dot sitting inside the source row's value box. Painted (all supplied by
 * the {@link Router}) as a cubic bezier from the tail dot, then a short straight
 * stub into the arrowhead — so the head runs horizontally regardless of the
 * curve's shape. The router still setPoints() so bounds and the arrowhead
 * decoration work. Paints at alpha 90 (clip-and-fade) when either endpoint is
 * scrolled out of its pane; the {@link Clipping} strategy stops the curve
 * exactly at the pane border. Routing, clipping, and painting share protocol
 * (setCurve-before-setPoints, endpoint-visibility fading), so all three live in
 * this one file.
 */
public class StateConnection extends PolylineConnection {

    private static final int FADED_ALPHA = 90;
    private static final int DOT_DIAMETER = 6;

    private final int laneIndex;
    private final Color baseColor;

    // Bezier end + control points in the connection's coordinates, set by the
    // router right before setPoints() (null only until the first routing pass).
    // The curve runs start..curveEnd; curveEnd->end is the fixed straight head stub
    // into the arrowhead (the tail dot is at start, painted straight from the curve).
    private Point curveEnd;
    private Point curveC1;
    private Point curveC2;

    public StateConnection(ChangeStatus sourceStatus, int laneIndex, PluginConfig config) {
        this.laneIndex = laneIndex;
        baseColor = config.palette().connectionColor(sourceStatus);
        setForegroundColor(baseColor);
        setLineWidth(1);
        PolygonDecoration arrowhead = new PolygonDecoration(); // filled TRIANGLE_TIP, inherits color
        arrowhead.setScale(9, 4);
        setTargetDecoration(arrowhead);
    }

    /** Jitter slot (0..LANES-1) spreading parallel cross-pane curves apart. */
    public int laneIndex() {
        return laneIndex;
    }

    /** Called by the router before setPoints(); the curve repaints with the points. */
    void setCurve(Point c1, Point c2, Point curveEnd) {
        this.curveEnd = curveEnd;
        this.curveC1 = c1;
        this.curveC2 = c2;
    }

    public void setHover(boolean on, PluginConfig config) {
        setLineWidth(on ? 2 : 1);
        setForegroundColor(on ? config.palette().hoverAccent() : baseColor);
    }

    public boolean bothEndpointsVisible() {
        return RevealUtil.endpointVisible(getSourceAnchor()) && RevealUtil.endpointVisible(getTargetAnchor());
    }

    @Override
    public void paint(Graphics graphics) {
        // Recomputed per paint; no state mutation while painting (no repaint loops).
        // Overriding paint (not paintFigure) so the arrowhead child fades too.
        if (!bothEndpointsVisible()) {
            graphics.setAlpha(FADED_ALPHA);
        }
        super.paint(graphics);
    }

    @Override
    protected void outlineShape(Graphics graphics) {
        PointList points = getPoints();
        if (points.size() < 2) {
            return;
        }
        Point start = points.getFirstPoint();
        Point end = points.getLastPoint();
        if (curveEnd == null || curveC1 == null || curveC2 == null) {
            graphics.drawPolyline(points); // no routing pass yet; degrade gracefully
        } else {
            // SWT Path (device resource): built per paint and disposed immediately.
            // The cubic straight from the tail dot, then a straight stub into the
            // arrowhead — one continuous path.
            Path path = new Path(Display.getCurrent());
            try {
                path.moveTo(start.x, start.y);
                path.cubicTo(curveC1.x, curveC1.y, curveC2.x, curveC2.y, curveEnd.x, curveEnd.y);
                path.lineTo(end.x, end.y);
                graphics.drawPath(path);
            } finally {
                path.dispose();
            }
        }
        // Tail dot: the pointer visibly sits inside the source row's value box.
        graphics.setBackgroundColor(graphics.getForegroundColor());
        graphics.fillOval(start.x - DOT_DIAMETER / 2, start.y - DOT_DIAMETER / 2, DOT_DIAMETER, DOT_DIAMETER);
    }

    // The tail dot pokes past the polyline hull; grow the damage/repaint bounds
    // to match (the control-point hull is covered by the router's point list).
    @Override
    public Rectangle getBounds() {
        if (bounds == null) {
            super.getBounds(); // computes the cached polyline bounds
            bounds = bounds.getExpanded(DOT_DIAMETER, DOT_DIAMETER);
        }
        return bounds;
    }

    private static Viewport viewportOf(IFigure owner) {
        return owner == null ? null : ViewportUtilities.getNearestEnclosingViewport(owner);
    }

    /**
     * Deterministic bezier router. Each edge is a cubic from the source tail dot,
     * then a short fixed horizontal stub into the arrowhead: the curve ends at
     * {@code curveEnd} (STUB outside the target box's near edge), and the
     * connection paints that stub as a straight segment so the head enters the
     * arrowhead horizontally regardless of the curve's shape.
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
    public static class Router extends AbstractRouter {

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

        public Router(Supplier<Rectangle> gutterAbsolute, ArcBaseline heapArcBaseline) {
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
            // the control points by the connection, not from these straight segments.
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
    }

    /**
     * Connection-layer clipping (modeled on the stock viewport-aware strategy but
     * clip-and-fade instead of hide): intra-heap edges clip to their shared
     * viewport; cross-pane edges clip to stackVisible + gutter + heapVisible so a
     * line whose endpoint scrolled away dies exactly at the pane border (the
     * connection itself paints faded); with BOTH endpoints out of view the edge is
     * skipped entirely (empty clip array).
     */
    public static class Clipping implements IClippingStrategy {

        private final ScrollPane stackPane;
        private final ScrollPane heapPane;
        private final Supplier<Rectangle> gutterAbsolute;

        public Clipping(ScrollPane stackPane, ScrollPane heapPane, Supplier<Rectangle> gutterAbsolute) {
            this.stackPane = stackPane;
            this.heapPane = heapPane;
            this.gutterAbsolute = gutterAbsolute;
        }

        @Override
        public Rectangle[] getClip(IFigure childFigure) {
            Rectangle[] clip = computeAbsoluteClip(childFigure);
            for (Rectangle rectangle : clip) {
                childFigure.translateToRelative(rectangle); // mirrors the stock strategy
            }
            return clip;
        }

        private Rectangle[] computeAbsoluteClip(IFigure childFigure) {
            if (!(childFigure instanceof Connection connection)
                    || connection.getSourceAnchor() == null || connection.getSourceAnchor().getOwner() == null
                    || connection.getTargetAnchor() == null || connection.getTargetAnchor().getOwner() == null) {
                return new Rectangle[] { absoluteBounds(childFigure) };
            }
            Viewport sourceViewport = viewportOf(connection.getSourceAnchor().getOwner());
            Viewport targetViewport = viewportOf(connection.getTargetAnchor().getOwner());
            if (sourceViewport == targetViewport) {
                if (sourceViewport == null) {
                    return new Rectangle[] { absoluteBounds(childFigure) };
                }
                return new Rectangle[] { RevealUtil.absoluteClientArea(sourceViewport) };
            }
            boolean sourceVisible = RevealUtil.endpointVisible(connection.getSourceAnchor());
            boolean targetVisible = RevealUtil.endpointVisible(connection.getTargetAnchor());
            if (!sourceVisible && !targetVisible) {
                return new Rectangle[0]; // paintChildren skips the connection entirely
            }
            return new Rectangle[] {
                    RevealUtil.absoluteClientArea(stackPane.getViewport()),
                    gutterAbsolute.get(),
                    RevealUtil.absoluteClientArea(heapPane.getViewport()) };
        }

        private static Rectangle absoluteBounds(IFigure figure) {
            Rectangle bounds = figure.getBounds().getCopy();
            figure.translateToAbsolute(bounds);
            return bounds;
        }
    }
}
