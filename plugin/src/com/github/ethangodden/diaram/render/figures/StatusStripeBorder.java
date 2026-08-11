package com.github.ethangodden.diaram.render.figures;

import org.eclipse.draw2d.AbstractBorder;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.geometry.Insets;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.swt.graphics.Color;

/** A 3 px vertical accent stripe at the row's left edge; a null color paints nothing. */
public final class StatusStripeBorder extends AbstractBorder {

    private static final Insets INSETS = new Insets(0, 7, 0, 0);
    private static final int STRIPE_WIDTH = 3;

    private final @Nullable Color color;

    public StatusStripeBorder(@Nullable Color color) {
        this.color = color;
    }

    @Override
    public Insets getInsets(IFigure figure) {
        return INSETS;
    }

    @Override
    public void paint(IFigure figure, Graphics graphics, Insets insets) {
        if (color == null) {
            return;
        }
        Rectangle area = getPaintRectangle(figure, insets);
        graphics.setBackgroundColor(color);
        graphics.fillRectangle(area.x, area.y, STRIPE_WIDTH, area.height);
    }
}
