package com.github.ethangodden.debugmemoryview.render.figures;

import org.eclipse.draw2d.BorderLayout;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.PositionConstants;
import org.eclipse.draw2d.ScrollPane;

import com.github.ethangodden.debugmemoryview.render.ColorPalette;
import com.github.ethangodden.debugmemoryview.render.FontKit;
import com.github.ethangodden.debugmemoryview.render.PluginConfig;

/** A diagram column: header label on top, ScrollPane filling the rest. */
public class ColumnFigure extends Figure {

    private final Label header;

    public ColumnFigure(String title, ScrollPane pane, PluginConfig config) {
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

    public Label header() {
        return header;
    }

    /** Chrome persists across rebuilds; re-apply theme colors on every render. */
    public void restyle(PluginConfig config) {
        ColorPalette palette = config.palette();
        FontKit fonts = config.fonts();
        setBackgroundColor(palette.columnBackground());
        header.setFont(fonts.header());
        header.setBackgroundColor(palette.headerBackground());
        header.setForegroundColor(palette.textForeground());
    }
}
