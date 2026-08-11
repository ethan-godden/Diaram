package com.github.ethangodden.diaram.render;

import java.util.function.Supplier;

import org.eclipse.draw2d.Connection;
import org.eclipse.draw2d.IClippingStrategy;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.ScrollPane;
import org.eclipse.draw2d.Viewport;
import org.eclipse.draw2d.ViewportUtilities;
import org.eclipse.draw2d.geometry.Rectangle;

/**
 * Connection-layer clipping (modeled on the stock viewport-aware strategy but
 * clip-and-fade instead of hide): intra-heap edges clip to their shared
 * viewport; cross-pane edges clip to stackVisible + gutter + heapVisible so a
 * line whose endpoint scrolled away dies exactly at the pane border (the
 * connection itself paints faded); with BOTH endpoints out of view the edge is
 * skipped entirely (empty clip array).
 */
public class MemoryClippingStrategy implements IClippingStrategy {

    private final ScrollPane stackPane;
    private final ScrollPane heapPane;
    private final Supplier<Rectangle> gutterAbsolute;

    public MemoryClippingStrategy(ScrollPane stackPane, ScrollPane heapPane, Supplier<Rectangle> gutterAbsolute) {
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
        Viewport sourceViewport = ViewportUtilities
                .getNearestEnclosingViewport(connection.getSourceAnchor().getOwner());
        Viewport targetViewport = ViewportUtilities
                .getNearestEnclosingViewport(connection.getTargetAnchor().getOwner());
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
