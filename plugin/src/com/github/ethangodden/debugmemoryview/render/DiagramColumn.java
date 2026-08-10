package com.github.ethangodden.debugmemoryview.render;

import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.ScrollPane;
import org.eclipse.draw2d.ToolbarLayout;
import org.eclipse.draw2d.geometry.Point;

import com.github.ethangodden.debugmemoryview.render.figures.ColumnFigure;

/**
 * One diagram column (stack or heap): a vertically-laid-out contents {@link Figure},
 * the {@link ScrollPane} that clips/scrolls it, and the {@link ColumnFigure} chrome
 * (header + border) around it. Stack and heap are otherwise identical wiring, so
 * this owns the creation, scroll-position save/restore, discard, and restyle that
 * would otherwise be duplicated per column in {@link DiagramController}.
 */
final class DiagramColumn {

    private final Figure contents;
    private final ScrollPane pane;
    private final ColumnFigure column;

    DiagramColumn(String title, int spacing, PluginConfig config) {
        contents = newVerticalContents(spacing);
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
        column = new ColumnFigure(title, pane, config);
    }

    Figure contents() {
        return contents;
    }

    ScrollPane pane() {
        return pane;
    }

    ColumnFigure column() {
        return column;
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

    void restyle(PluginConfig config) {
        column.restyle(config);
    }

    private static Figure newVerticalContents(int spacing) {
        Figure contents = new Figure();
        ToolbarLayout layout = new ToolbarLayout(false);
        layout.setSpacing(spacing);
        layout.setStretchMinorAxis(false); // frames / heap boxes hug their content width
        contents.setLayoutManager(layout);
        contents.setBorder(new MarginBorder(8));
        return contents;
    }
}
