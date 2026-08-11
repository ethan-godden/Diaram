package com.github.ethangodden.diaram.render;

import org.eclipse.draw2d.AbstractLayout;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Rectangle;

/**
 * Splits the columns layer into stack | center gutter (a fixed divider) | heap.
 *
 * The stack column always takes its natural (guarded) content width; everything
 * to its right is the gutter plus the heap column, and the heap column carries
 * the heap boxes flush-left with any surplus showing as trailing whitespace on
 * its right.
 *
 * Width is surrendered in priority order as the view narrows:
 * <ol>
 * <li>first the trailing whitespace to the right of the heap boxes,</li>
 * <li>then the center gutter, from {@link #GUTTER} down to {@link #MIN_GUTTER},</li>
 * <li>and once both are exhausted the layer holds its natural minimum width
 *     ({@code stackNeed + MIN_GUTTER + heapNeed}) and the whole view scrolls
 *     horizontally instead of shrinking or clipping the boxes.</li>
 * </ol>
 * That minimum width is what makes the enclosing FigureCanvas viewport overflow
 * (and therefore scroll) once it is narrower than the diagram: a width-tracking
 * viewport sizes its contents to {@code max(available, contents-minimum)}, so a
 * real minimum here becomes the scroll region. The gutter stays wide by default
 * so the bezier reference curves crossing it remain distinguishable.
 */
public class ColumnsLayout extends AbstractLayout {

    public static final int GUTTER = 96;
    /** The gutter never shrinks below this: room for the 6 px divider plus a thin arrow lane. */
    public static final int MIN_GUTTER = 16;
    public static final int SASH_WIDTH = 6;

    // Past this a stack frame's boxes ellipsize rather than widening the scroll
    // region without bound — keeps one very wide frame from dominating the view.
    private static final int MAX_PROTECTED_WIDTH = 360;
    private static final int MIN_HEIGHT = 120;

    private final IFigure stackColumn;
    private final IFigure sash;
    private final IFigure heapColumn;
    private final IFigure stackContents;
    private final IFigure heapContents;

    public ColumnsLayout(IFigure stackColumn, IFigure sash, IFigure heapColumn,
            IFigure stackContents, IFigure heapContents) {
        this.stackColumn = stackColumn;
        this.sash = sash;
        this.heapColumn = heapColumn;
        this.stackContents = stackContents;
        this.heapContents = heapContents;
    }

    @Override
    protected Dimension calculatePreferredSize(IFigure container, int wHint, int hHint) {
        return new Dimension(Math.max(wHint, minWidth()), Math.max(hHint, MIN_HEIGHT));
    }

    @Override
    public Dimension getMinimumSize(IFigure container, int wHint, int hHint) {
        return new Dimension(minWidth(), MIN_HEIGHT);
    }

    @Override
    public void layout(IFigure container) {
        Rectangle area = container.getClientArea();
        int stackWidth = stackNeed();
        int heapNeed = heapNeed();

        // The gutter yields only after the heap-right whitespace is gone: at full
        // width it holds GUTTER (surplus becomes trailing whitespace inside the
        // heap column); as the view tightens it shrinks toward MIN_GUTTER; below
        // that the layer is at its minimum width and the viewport scrolls.
        int gutter = Math.clamp((long) area.width - stackWidth - heapNeed, MIN_GUTTER, GUTTER);

        stackColumn.setBounds(new Rectangle(area.x, area.y, stackWidth, area.height));
        sash.setBounds(new Rectangle(area.x + stackWidth + (gutter - SASH_WIDTH) / 2, area.y,
                SASH_WIDTH, area.height));
        heapColumn.setBounds(new Rectangle(area.x + stackWidth + gutter, area.y,
                Math.max(0, area.width - stackWidth - gutter), area.height));
    }

    /** Natural minimum width: both columns fit their boxes with only the minimum gutter between. */
    private int minWidth() {
        return stackNeed() + MIN_GUTTER + heapNeed();
    }

    /** Stack frames are unbounded, so cap their protected width (they ellipsize past it). */
    private int stackNeed() {
        return Math.min(MAX_PROTECTED_WIDTH, contentWidth(stackContents));
    }

    /** Heap boxes are already clamped to a max, so protect their natural width in full. */
    private int heapNeed() {
        return contentWidth(heapContents);
    }

    /**
     * The column contents' natural width — measured with no width hint, so it reflects the
     * boxes' un-ellipsized width regardless of the current (viewport-tracked) column width.
     */
    private static int contentWidth(IFigure contents) {
        return contents.getPreferredSize(-1, -1).width;
    }
}
