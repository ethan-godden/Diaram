package com.github.ethangodden.diaram.render.figures;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.LineBorder;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.PositionConstants;
import org.eclipse.draw2d.ToolbarLayout;

import com.github.ethangodden.diaram.model.MemorySnapshot.DisplayableStruct;
import com.github.ethangodden.diaram.model.MemorySnapshot.DisplayableVariable;
import com.github.ethangodden.diaram.render.ColorPalette;
import com.github.ethangodden.diaram.render.Ellipsis;
import com.github.ethangodden.diaram.render.FontKit;
import com.github.ethangodden.diaram.render.PluginConfig;

/**
 * Tooltip body for a reference row: the target struct's header line plus the
 * first few of its field/element/char lines. Built lazily on first hover from
 * the cached snapshot — lets the user read what an arrow points to without
 * scrolling the heap pane. Uniform over the neutral {@link DisplayableStruct}:
 * each field renders "label = value", box-only fields (enum constant marker,
 * no type) render just their label, and an unexplored struct shows
 * a single "(not explored)" line.
 */
public class ObjectPreviewFigure extends Figure {

    private static final int MAX_LINES = 5;
    private static final int MAX_LINE_CHARS = 80;

    public ObjectPreviewFigure(DisplayableStruct struct, PluginConfig config) {
        ColorPalette palette = config.palette();
        FontKit fonts = config.fonts();
        ToolbarLayout layout = new ToolbarLayout(false);
        layout.setStretchMinorAxis(true);
        setLayoutManager(layout);
        setOpaque(true);
        setBackgroundColor(palette.boxBackground());
        setBorder(new LineBorder(palette.boxBorder(), 1));

        Label header = new Label(struct.type());
        header.setLabelAlignment(PositionConstants.LEFT);
        header.setFont(fonts.header());
        header.setForegroundColor(palette.textForeground());
        header.setBorder(new MarginBorder(3, 6, 3, 6));
        add(header);

        List<String> lines = new ArrayList<>();
        int omitted = collectLines(struct, lines);
        for (String line : lines) {
            add(bodyLine(line, config));
        }
        if (omitted > 0) {
            Label more = bodyLine("… +" + omitted + " more", config);
            more.setForegroundColor(palette.mutedForeground());
            add(more);
        }
    }

    /** Fills up to MAX_LINES display lines; returns how many content lines were omitted. */
    private static int collectLines(DisplayableStruct struct, List<String> lines) {
        if (!struct.explored()) {
            lines.add("(not explored)");
            return 0;
        }
        List<DisplayableVariable> fields = struct.variables();
        int shown = Math.min(fields.size(), MAX_LINES);
        for (int i = 0; i < shown; i++) {
            DisplayableVariable field = fields.get(i);
            lines.add(lineOf(field));
        }
        return fields.size() + struct.omitted() - shown;
    }

    /** "label = value", or just the label for a box-only field (enum constant marker). */
    private static String lineOf(DisplayableVariable field) {
        if (field.type() == null) {
            return field.label(); // box-only content row (enum constant name)
        }
        return field.label() + " = " + Ellipsis.valueText(field.value(), MAX_LINE_CHARS);
    }

    private static Label bodyLine(String text, PluginConfig config) {
        Label label = new Label(text);
        label.setLabelAlignment(PositionConstants.LEFT);
        label.setFont(config.fonts().value());
        label.setForegroundColor(config.palette().textForeground());
        label.setBorder(new MarginBorder(1, 6, 1, 6));
        return label;
    }
}
