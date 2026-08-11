package com.github.ethangodden.diaram.render;

import org.eclipse.draw2d.ConnectionAnchor;
import org.eclipse.draw2d.IFigure;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.draw2d.ScrollPane;
import org.eclipse.draw2d.Viewport;
import org.eclipse.draw2d.ViewportUtilities;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.Rectangle;

/** Scroll-a-figure-into-view and viewport visibility helpers (all absolute coordinates). */
public final class RevealUtil {

    private static final int REVEAL_MARGIN = 24;

    private RevealUtil() {
    }

    /** The viewport's visible client area in absolute coordinates (stock strategy recipe). */
    public static Rectangle absoluteClientArea(Viewport viewport) {
        Rectangle area = viewport.getClientArea();
        viewport.translateToParent(area);
        viewport.translateToAbsolute(area);
        return area;
    }

    /** True when the anchor's reference point lies inside its enclosing viewport's visible area. */
    public static boolean endpointVisible(@Nullable ConnectionAnchor anchor) {
        if (anchor == null || anchor.getOwner() == null) {
            return true;
        }
        Viewport viewport = ViewportUtilities.getNearestEnclosingViewport(anchor.getOwner());
        if (viewport == null) {
            return true;
        }
        return absoluteClientArea(viewport).contains(anchor.getReferencePoint());
    }

    /** Scrolls the pane (both axes) so the target is visible; RangeModel clamps the values. */
    public static void reveal(ScrollPane pane, IFigure target) {
        Viewport viewport = pane.getViewport();
        Rectangle targetBounds = target.getBounds().getCopy();
        target.translateToAbsolute(targetBounds);
        Rectangle visible = absoluteClientArea(viewport);
        if (visible.contains(targetBounds)) {
            return;
        }
        Point viewLocation = viewport.getViewLocation();
        viewport.setViewLocation(viewLocation.x + (targetBounds.x - visible.x - REVEAL_MARGIN),
                viewLocation.y + (targetBounds.y - visible.y - REVEAL_MARGIN));
    }

    /**
     * Scrolls a viewport horizontally only, so the target's x-range is in view; RangeModel
     * clamps the value. Used to bring the heap column into the window when the whole view is
     * scrolled off the heap (the vertical axis lives inside the per-column pane, not here).
     */
    public static void revealHorizontally(Viewport viewport, IFigure target) {
        Rectangle targetBounds = target.getBounds().getCopy();
        target.translateToAbsolute(targetBounds);
        Rectangle visible = absoluteClientArea(viewport);
        if (targetBounds.x >= visible.x && targetBounds.right() <= visible.right()) {
            return;
        }
        Point viewLocation = viewport.getViewLocation();
        viewport.setViewLocation(viewLocation.x + (targetBounds.x - visible.x - REVEAL_MARGIN),
                viewLocation.y);
    }
}
