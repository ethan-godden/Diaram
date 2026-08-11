package com.github.ethangodden.diaram.render;

import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.PolygonDecoration;
import org.eclipse.draw2d.PolylineConnection;
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
 * the router) as a cubic bezier from the tail dot, then a short straight stub
 * into the arrowhead — so the head runs horizontally regardless of the curve's
 * shape. The router still setPoints() so bounds and the arrowhead decoration
 * work. Paints at alpha 90 (clip-and-fade) when either endpoint is scrolled out
 * of its pane; the clipping strategy stops the curve exactly at the pane border.
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
    public void setCurve(Point c1, Point c2, Point curveEnd) {
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
}
