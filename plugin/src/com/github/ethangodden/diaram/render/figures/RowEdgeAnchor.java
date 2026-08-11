package com.github.ethangodden.diaram.render.figures;

import java.util.function.Function;

import org.eclipse.draw2d.AbstractConnectionAnchor;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.Rectangle;

/**
 * A connection anchor pinned to one point of its owner figure's absolute
 * bounds, chosen by {@code pick} — {@link Rectangle#getCenter} for a value box
 * (the tail dot sits inside the cell), {@link Rectangle#getLeft} /
 * {@link Rectangle#getRight} for a heap box's first body row (arrows land on
 * the near edge). The figures mint these themselves
 * ({@link VariableRowFigure#sourceAnchor}, {@link HeapObjectFigure#targetAnchor}),
 * so the arrow-attachment policy stays internal to the figure tree. Extending
 * AbstractConnectionAnchor gives the stock ancestor-listener chain: a pane
 * scroll physically moves the contents figure, fires figureMoved up the tree,
 * and re-routes every attached connection with no manual re-anchoring.
 */
class RowEdgeAnchor extends AbstractConnectionAnchor {

    private final Function<Rectangle, Point> pick;

    RowEdgeAnchor(IFigure owner, Function<Rectangle, Point> pick) {
        super(owner);
        this.pick = pick;
    }

    @Override
    public Point getLocation(Point reference) {
        Rectangle bounds = getOwner().getBounds().getCopy();
        getOwner().translateToAbsolute(bounds);
        return pick.apply(bounds);
    }
}
