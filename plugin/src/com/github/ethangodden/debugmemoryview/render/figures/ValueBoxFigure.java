package com.github.ethangodden.debugmemoryview.render.figures;

import org.eclipse.draw2d.CompoundBorder;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.LineBorder;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.PositionConstants;
import org.eclipse.jdt.annotation.Nullable;

import com.github.ethangodden.debugmemoryview.model.diff.ChangeStatus;
import com.github.ethangodden.debugmemoryview.render.ColorPalette;
import com.github.ethangodden.debugmemoryview.render.FontKit;

/**
 * The literal value cell of a variable row: a 1 px palette-border box holding
 * the (pre-ellipsized) primitive text, "?" for unreadables, and nothing for
 * references and nulls — a reference's connection tail starts inside this box.
 * Transparent so the row's status tint / hover highlight shows through.
 *
 * The {@link #MIN_WIDTH} floor is applied by {@link VariableRowFigure}'s
 * layout, NOT by overriding getPreferredSize() here: Label centers its text by
 * offsetting with (size - preferredSize), so a floored preferred size would
 * zero that offset and pin short text hard-left in a 40 px cell.
 */
public class ValueBoxFigure extends Label {

    public static final int MIN_WIDTH = 40;

    public ValueBoxFigure(@Nullable String text, ChangeStatus status, ColorPalette palette, FontKit fonts) {
        super(text == null ? "" : text);
        setLabelAlignment(PositionConstants.CENTER);
        setFont(fonts.name());
        setForegroundColor(palette.statusForeground(status));
        setBorder(new CompoundBorder(new LineBorder(palette.boxBorder(), 1), new MarginBorder(1, 4, 1, 4)));
    }
}
