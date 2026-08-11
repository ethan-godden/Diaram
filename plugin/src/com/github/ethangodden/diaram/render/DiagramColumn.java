package com.github.ethangodden.diaram.render;

import org.eclipse.draw2d.BorderLayout;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.PositionConstants;
import org.eclipse.draw2d.ScrollPane;
import org.eclipse.draw2d.ToolbarLayout;
import org.eclipse.draw2d.geometry.Point;

/**
 * One diagram column (stack or heap): a header label on top and, filling the
 * rest, the {@link ScrollPane} that clips/scrolls a vertically-laid-out
 * contents {@link Figure}. Stack and heap are otherwise identical wiring, so
 * this owns the creation, scroll-position save/restore, discard, and restyle
 * that would otherwise be duplicated per column in {@link DiagramController}.
 */
final class DiagramColumn extends Figure {

    private final Figure contents;
    private final ScrollPane pane;
    private final Label header;

    DiagramColumn(String title, int spacing, int rightPadding, PluginConfig config) {
        contents = newVerticalContents(spacing, rightPadding);
        pane = new ScrollPane();
        // No stock bars anywhere: ScrollThumbOverlay paints auto-hiding thumbs instead.
        // Scrolling itself (wheel routing, RangeModels, reveal) never touches the bar figures.
        pane.setScrollBarVisibility(ScrollPane.NEVER);
        // Contents track the viewport width: a narrowing column SHRINKS each frame
        // to min(natural, available) — headers/identifiers ellipsize, value boxes
        // survive — instead of clipping at the pane edge. Below the figures'
        // box-preserving minimums the contents overflow again, so the horizontal
        // thumb + Shift+wheel stay wired (dormant until that extreme).
        pane.getViewport().setContentsTracksWidth(true);
        pane.setContents(contents);

        setLayoutManager(new BorderLayout());
        setOpaque(true);
        header = new Label(title);
        header.setLabelAlignment(PositionConstants.LEFT);
        header.setOpaque(true);
        header.setBorder(new MarginBorder(4, 8, 4, 8));
        add(header, BorderLayout.TOP);
        add(pane, BorderLayout.CENTER);
        restyle(config);
    }

    /** The raw contents figure — exposed only for {@link ColumnsLayout}'s natural-width wiring. */
    Figure contents() {
        return contents;
    }

    /** Adds a top-level child (a frame, or the heap body); {@link #discard()} is the removal path. */
    void addContent(IFigure figure) {
        contents.add(figure);
    }

    ScrollPane pane() {
        return pane;
    }

    void setHeaderText(String text) {
        header.setText(text);
    }

    Point saveScroll() {
        return pane.getViewport().getViewLocation().getCopy();
    }

    void restoreScroll(Point location) {
        pane.getViewport().setViewLocation(location);
    }

    void discard() {
        contents.removeAll();
    }

    /** Chrome persists across rebuilds; re-apply theme colors on every render. */
    void restyle(PluginConfig config) {
        ColorPalette palette = config.palette();
        setBackgroundColor(palette.columnBackground());
        header.setFont(config.fonts().header());
        header.setBackgroundColor(palette.headerBackground());
        header.setForegroundColor(palette.textForeground());
    }

    private static Figure newVerticalContents(int spacing, int rightPadding) {
        Figure contents = new Figure();
        ToolbarLayout layout = new ToolbarLayout(false);
        layout.setSpacing(spacing);
        layout.setStretchMinorAxis(false); // frames / heap boxes hug their content width
        contents.setLayoutManager(layout);
        contents.setBorder(new MarginBorder(8, 8, 8, rightPadding));
        return contents;
    }
}
