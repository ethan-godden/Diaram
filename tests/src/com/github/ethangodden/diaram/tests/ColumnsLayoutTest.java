package com.github.ethangodden.diaram.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Rectangle;

import org.junit.jupiter.api.Test;

import com.github.ethangodden.diaram.render.ColumnsLayout;

/**
 * JUnit 5 tests for {@link ColumnsLayout}'s width-surrender ladder: the stack
 * keeps its natural (guarded) width, surplus shows as trailing whitespace to the
 * right of the heap, the gutter yields next, and the reported minimum width
 * ({@code stackNeed + MIN_GUTTER + heapNeed}) is what the enclosing viewport
 * overflows to trigger whole-view horizontal scrolling. Draw2d Figures are driven
 * directly (no SWT Display needed — nothing paints).
 */
public class ColumnsLayoutTest {

    // Mirrors the private ColumnsLayout guard; a stack wider than this ellipsizes.
    private static final int MAX_PROTECTED_WIDTH = 360;

    // ---------- fixture ----------
    /** A {@link ColumnsLayout} wired to bare Draw2d figures; nothing paints. */
    private static final class Fixture {
        final IFigure stackColumn = new Figure();
        final IFigure sash = new Figure();
        final IFigure heapColumn = new Figure();
        final IFigure stackContents = new Figure();
        final IFigure heapContents = new Figure();
        final IFigure container = new Figure();
        final ColumnsLayout layout =
                new ColumnsLayout(stackColumn, sash, heapColumn, stackContents, heapContents);

        Fixture(int stackNatural, int heapNatural) {
            stackContents.setPreferredSize(new Dimension(stackNatural, 100));
            heapContents.setPreferredSize(new Dimension(heapNatural, 100));
        }

        void layoutAt(int width) {
            container.setBounds(new Rectangle(0, 0, width, 400));
            layout.layout(container);
        }

        /** The horizontal gap the layout leaves between the two columns. */
        int gutter() {
            return b(heapColumn).x - b(stackColumn).right();
        }
    }

    private static Rectangle b(IFigure f) {
        return f.getBounds();
    }

    // ---------- tests ----------

    /** Wide viewport: gutter is full, stack is natural, surplus is heap-right whitespace. */
    @Test
    void testWideLeavesTrailingWhitespaceRightOfHeap() {
        Fixture t = new Fixture(200, 300);
        t.layoutAt(800);
        assertEquals(200, b(t.stackColumn).width, "wide: stack keeps its natural width");
        assertEquals(0, b(t.stackColumn).x, "wide: stack sits at the left edge");
        assertEquals(ColumnsLayout.GUTTER, t.gutter(), "wide: gutter is full");
        assertEquals(800, b(t.heapColumn).right(), "wide: heap column fills to the right edge");
        // Heap boxes are flush-left (ToolbarLayout ALIGN_TOPLEFT), so the column's
        // surplus over its content width is whitespace on the RIGHT of the boxes.
        assertTrue(b(t.heapColumn).width > 300, "wide: heap column is wider than its content (trailing whitespace)");
    }

    /** Narrowing past the whitespace shrinks the gutter next, never the columns. */
    @Test
    void testGutterYieldsAfterWhitespace() {
        Fixture t = new Fixture(200, 300);
        // naturalMin = 200 + 16 + 300 = 516; at 560 the gutter must be mid-range.
        t.layoutAt(560);
        assertEquals(200, b(t.stackColumn).width, "medium: stack still natural");
        assertEquals(60, t.gutter(), "medium: gutter absorbed the shrink (560-500)");
        assertEquals(300, b(t.heapColumn).width, "medium: heap column is exactly its content (no trailing whitespace)");
        assertTrue(t.gutter() < ColumnsLayout.GUTTER && t.gutter() > ColumnsLayout.MIN_GUTTER,
                "medium: gutter is between MIN_GUTTER and GUTTER");
    }

    /** At the natural minimum width the gutter bottoms out; below it the viewport scrolls. */
    @Test
    void testAtMinimumGutterBottomsOut() {
        Fixture t = new Fixture(200, 300);
        t.layoutAt(516);
        assertEquals(200, b(t.stackColumn).width, "min: stack natural");
        assertEquals(ColumnsLayout.MIN_GUTTER, t.gutter(), "min: gutter at MIN_GUTTER");
        assertEquals(300, b(t.heapColumn).width, "min: heap exactly its content");
        assertEquals(516, b(t.heapColumn).right(), "min: everything fits with nothing to spare");
    }

    /** getMinimumSize is stackNeed + MIN_GUTTER + heapNeed — the scroll trigger. */
    @Test
    void testMinimumSizeDrivesScroll() {
        Fixture t = new Fixture(200, 300);
        Dimension min = t.layout.getMinimumSize(t.container, -1, -1);
        assertEquals(200 + ColumnsLayout.MIN_GUTTER + 300, min.width, "min width = stackNeed + MIN_GUTTER + heapNeed");
    }

    /** A frame wider than the guard is capped (it ellipsizes) so it can't grow the scroll region unbounded. */
    @Test
    void testPathologicalStackIsGuarded() {
        Fixture t = new Fixture(500, 300);
        Dimension min = t.layout.getMinimumSize(t.container, -1, -1);
        assertEquals(MAX_PROTECTED_WIDTH + ColumnsLayout.MIN_GUTTER + 300, min.width,
                "guard: over-wide stack is capped at MAX_PROTECTED_WIDTH in the min width");
        t.layoutAt(1200);
        assertEquals(MAX_PROTECTED_WIDTH, b(t.stackColumn).width, "guard: stack column never exceeds the guard width");
    }

    /** The divider is centered in the gutter, between the two columns. */
    @Test
    void testSashCentersInGutter() {
        Fixture t = new Fixture(200, 300);
        t.layoutAt(800);
        assertEquals(ColumnsLayout.SASH_WIDTH, b(t.sash).width, "sash: fixed narrow width");
        int sashCenter = b(t.sash).x + b(t.sash).width / 2;
        int gutterCenter = b(t.stackColumn).right() + t.gutter() / 2;
        assertEquals(gutterCenter, sashCenter, "sash: centered in the gutter");
        assertTrue(b(t.sash).x >= b(t.stackColumn).right() && b(t.sash).right() <= b(t.heapColumn).x,
                "sash: sits between the columns");
    }
}
