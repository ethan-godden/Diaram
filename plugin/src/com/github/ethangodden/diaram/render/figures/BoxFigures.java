package com.github.ethangodden.diaram.render.figures;

import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.MouseEvent;
import org.eclipse.draw2d.MouseListener;
import org.eclipse.draw2d.PositionConstants;

import com.github.ethangodden.diaram.render.ColorPalette;
import com.github.ethangodden.diaram.render.FontKit;
import com.github.ethangodden.diaram.render.PluginConfig;

/** Shared header + toggle wiring for the collapsible box figures (heap objects, stack frames, statics). */
final class BoxFigures {

    private BoxFigures() {
    }

    /** The "▾/▸ title" header label with the collapsible-box chrome: opaque header band, LEFT alignment. */
    static Label collapsibleHeader(String title, boolean expanded, PluginConfig config) {
        ColorPalette palette = config.palette();
        FontKit fonts = config.fonts();
        Label header = new Label((expanded ? "▾ " : "▸ ") + title);
        header.setLabelAlignment(PositionConstants.LEFT);
        header.setFont(fonts.header());
        header.setOpaque(true);
        header.setBackgroundColor(palette.headerBackground());
        header.setForegroundColor(palette.textForeground());
        header.setBorder(new MarginBorder(3, 6, 3, 6));
        return header;
    }

    /** Wires single left-click on {@code header} to run {@code onToggle}; a null toggle is a no-op. */
    static void attachToggle(Label header, Runnable onToggle) {
        if (onToggle == null) {
            return;
        }
        header.addMouseListener(new MouseListener.Stub() {
            @Override
            public void mousePressed(MouseEvent me) {
                if (me.button == 1) {
                    me.consume();
                    onToggle.run();
                }
            }
        });
    }
}
