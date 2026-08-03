package com.github.ethangodden.debugmemoryview.render.figures;

import org.eclipse.draw2d.Border;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.LineBorder;
import org.eclipse.draw2d.ToolbarLayout;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.swt.graphics.Color;

import com.github.ethangodden.debugmemoryview.render.ColorPalette;
import com.github.ethangodden.debugmemoryview.render.FontKit;

/**
 * A variable container: an opaque, bordered box with a collapsible "▾/▸ title"
 * header and a body of variable rows. Stack frames are exactly this; heap
 * objects and arrays extend it ({@link HeapObjectFigure}) with hover /
 * width-clamp behaviour. Containers carry no change status — only variable
 * rows do.
 *
 * The border sits FLUSH against the rows — no inner margin — so a row's
 * value box touches the container border, matching a stack frame. Single left-
 * click on the ▾/▸ header toggles the box collapsed to header-only.
 */
public class ContainerFigure extends Figure {

    protected final ColorPalette palette;
    protected final Label header;
    protected final Figure body;

    public ContainerFigure(String title, boolean expanded,
            ColorPalette palette, FontKit fonts, @Nullable Runnable onToggle) {
        this.palette = palette;

        ToolbarLayout layout = new ToolbarLayout(false);
        layout.setStretchMinorAxis(true);
        setLayoutManager(layout);
        setOpaque(true);
        setBackgroundColor(palette.boxBackground());
        setBorder(borderFor(palette, false));

        header = BoxFigures.collapsibleHeader(title, expanded, palette, fonts);
        add(header);

        body = new Figure();
        ToolbarLayout bodyLayout = new ToolbarLayout(false);
        bodyLayout.setStretchMinorAxis(true);
        body.setLayoutManager(bodyLayout);
        if (expanded) {
            add(body);
        }

        BoxFigures.attachToggle(header, onToggle);
    }

    public void addRow(IFigure row) {
        body.add(row);
    }

    /**
     * The 1 px box border, flush against the rows. {@code hover} recolors it to
     * the accent while keeping the SAME width and style, so a hover or reveal
     * never shifts the box geometry (no inner margin needed to reserve the swap).
     */
    static Border borderFor(ColorPalette palette, boolean hover) {
        Color color = hover ? palette.hoverAccent() : palette.boxBorder();
        return new LineBorder(color, 1);
    }
}
