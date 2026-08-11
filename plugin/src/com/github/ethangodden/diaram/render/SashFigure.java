package com.github.ethangodden.diaram.render;

import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.swt.graphics.Color;

/**
 * Fixed divider line centered in the gutter between the stack and heap columns.
 * The columns are content-driven (the stack takes its natural width, the heap
 * takes the rest), so there is no ratio to drag: this is a plain visual marker
 * of the stack/heap boundary and of the lane the cross-pane arrows swing
 * through. {@link ColumnsLayout} positions it.
 */
public class SashFigure extends Figure {

    private @Nullable Color lineColor;

    public void setLineColor(@Nullable Color lineColor) {
        this.lineColor = lineColor;
        repaint();
    }

    @Override
    protected void paintFigure(Graphics graphics) {
        if (lineColor == null) {
            return;
        }
        Rectangle bounds = getBounds();
        graphics.setBackgroundColor(lineColor);
        graphics.fillRectangle(bounds.x + bounds.width / 2 - 1, bounds.y, 2, bounds.height);
    }
}
