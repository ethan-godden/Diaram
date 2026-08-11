package com.github.ethangodden.diaram.render.figures;

import org.eclipse.draw2d.Border;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.LineBorder;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.MouseEvent;
import org.eclipse.draw2d.MouseListener;
import org.eclipse.draw2d.PositionConstants;
import org.eclipse.draw2d.ToolbarLayout;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.swt.graphics.Color;

import com.github.ethangodden.diaram.render.ColorPalette;
import com.github.ethangodden.diaram.render.FontKit;
import com.github.ethangodden.diaram.render.PluginConfig;

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

    protected final PluginConfig config;
    protected final Label header;
    protected final Figure body;

    public ContainerFigure(String title, boolean expanded,
            PluginConfig config, @Nullable Runnable onToggle) {
        this.config = config;

        ToolbarLayout layout = new ToolbarLayout(false);
        layout.setStretchMinorAxis(true);
        setLayoutManager(layout);
        setOpaque(true);
        setBackgroundColor(config.palette().boxBackground());
        setBorder(borderFor(config, false));

        header = collapsibleHeader(title, expanded, config);
        add(header);

        body = new Figure();
        ToolbarLayout bodyLayout = new ToolbarLayout(false);
        bodyLayout.setStretchMinorAxis(true);
        body.setLayoutManager(bodyLayout);
        if (expanded) {
            add(body);
        }

        attachToggle(header, onToggle);
    }

    public void addRow(IFigure row) {
        body.add(row);
    }

    /**
     * The 1 px box border, flush against the rows. {@code hover} recolors it to
     * the accent while keeping the SAME width and style, so a hover or reveal
     * never shifts the box geometry (no inner margin needed to reserve the swap).
     */
    static Border borderFor(PluginConfig config, boolean hover) {
        ColorPalette palette = config.palette();
        Color color = hover ? palette.hoverAccent() : palette.boxBorder();
        return new LineBorder(color, 1);
    }

    /** The "▾/▸ title" header label with the collapsible-box chrome: opaque header band, LEFT alignment. */
    private static Label collapsibleHeader(String title, boolean expanded, PluginConfig config) {
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
    private static void attachToggle(Label header, @Nullable Runnable onToggle) {
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
