package com.github.ethangodden.debugmemoryview.render.figures;

import org.eclipse.draw2d.AbstractLayout;
import org.eclipse.draw2d.CompoundBorder;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.Label;
import org.eclipse.draw2d.MarginBorder;
import org.eclipse.draw2d.PositionConstants;
import org.eclipse.draw2d.geometry.Dimension;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.swt.graphics.Color;

import com.github.ethangodden.debugmemoryview.model.diff.ChangeStatus;
import com.github.ethangodden.debugmemoryview.render.ColorPalette;
import com.github.ethangodden.debugmemoryview.render.FontKit;
import com.github.ethangodden.debugmemoryview.render.PluginConfig;

/**
 * One row = one variable / field / static field / array element, drawn as
 * "identifier : [box]" — the {@link ValueBoxFigure} is the literal memory
 * cell (no type text; types live in the heap box headers). The box is pinned
 * to the row's right edge (no right inset, so it touches the container's
 * inner border) at its natural width and the identifier hugs its box (label
 * right edge {@code GAP} px left of the box border), so any leftover row
 * width sits at the row's LEFT — a wide header never opens a gap between the
 * colon and the box. A null name omits the label (STRING / BOXED /
 * enum-constant content rows are box-only).
 * Reference rows carry the target heap box token and participate in hover/connection
 * wiring. Zero vertical margin so consecutive cells stack contiguously; the
 * hover highlight only swaps colors and never changes insets.
 */
public class VariableRowFigure extends Figure {

    private static final int GAP = 4;
    /**
     * Widest box the row's MINIMUM preserves at natural width. Identifier cells
     * stay whole under squeeze (the identifier ellipsizes instead); wider
     * content-style boxes (long STRING/BOXED text) keep shrinking to "…" past
     * this. Must stay well under {@link HeapObjectFigure#MAX_WIDTH} minus the
     * row insets, or ToolbarLayout's minimum floor would push a wide box-only
     * row past the clamped container (overflow-clipped, never "…"-truncated).
     */
    private static final int BOX_KEEP_MAX = 120;

    private final @Nullable String targetToken; // null for primitives / null refs / dangling / unreadables
    private final @Nullable Label nameLabel; // null for box-only rows
    private final ValueBoxFigure valueBox;
    private final @Nullable Color baseBackground; // null when the row has no tint

    public VariableRowFigure(@Nullable String name, @Nullable String boxText, @Nullable String targetToken,
            ChangeStatus status, PluginConfig config) {
        this.targetToken = targetToken;
        ColorPalette palette = config.palette();
        FontKit fonts = config.fonts();
        setLayoutManager(new RowLayout());
        setBorder(new CompoundBorder(new StatusStripeBorder(palette.stripe(status)),
                new MarginBorder(0, 8, 0, 0)));
        if (name != null) {
            nameLabel = new Label(name + " :");
            nameLabel.setLabelAlignment(PositionConstants.LEFT);
            nameLabel.setFont(fonts.name());
            nameLabel.setForegroundColor(palette.statusForeground(status));
            add(nameLabel);
        } else {
            nameLabel = null;
        }
        valueBox = new ValueBoxFigure(boxText, status, config);
        add(valueBox);
        baseBackground = palette.rowTint(status);
        if (baseBackground != null) {
            setOpaque(true);
            setBackgroundColor(baseBackground);
        }
    }

    /** The target heap box token this reference row points at, or null (non-reference row). */
    public @Nullable String targetToken() {
        return targetToken;
    }

    /** The value cell; connection tails anchor to its center. */
    public ValueBoxFigure valueBox() {
        return valueBox;
    }

    public void setHoverHighlight(boolean hover, PluginConfig config) {
        if (hover) {
            setOpaque(true);
            setBackgroundColor(config.palette().hoverRowBackground());
        } else {
            setBackgroundColor(baseBackground);
            setOpaque(baseBackground != null);
        }
    }

    /**
     * Pins the value box to the right edge at its natural width (floored at
     * {@link ValueBoxFigure#MIN_WIDTH}) and snugs the label against it, its
     * right edge GAP px left of the box border — leftover width lands at the
     * row's left; preferred width stays label+box natural widths. Unlike a
     * stock BorderLayout, a box wider than the client area (a MAX_WIDTH-clamped
     * container) shrinks to fit instead of overflowing left, so the Label's own
     * "…" truncation kicks in; the label never drops below its ellipsis minimum.
     */
    private final class RowLayout extends AbstractLayout {

        @Override
        protected Dimension calculatePreferredSize(IFigure container, int wHint, int hHint) {
            Dimension size = sumOf(boxPreferred(),
                    nameLabel != null ? nameLabel.getPreferredSize() : null);
            return size.expand(container.getInsets().getWidth(), container.getInsets().getHeight());
        }

        @Override
        public Dimension getMinimumSize(IFigure container, int wHint, int hHint) {
            // Box-preserving minimum: the box holds its natural width (capped at
            // BOX_KEEP_MAX) and only the identifier shrinks — its Label minimum is
            // the ellipsis minimum — so squeezing a container collapses the slack
            // left of the identifier first, then "…"-truncates the identifier, and
            // only below this floor does the box itself get eaten (edge clipping).
            Dimension box = boxPreferred();
            box.width = Math.min(box.width, BOX_KEEP_MAX);
            Dimension size = sumOf(box, nameLabel != null ? nameLabel.getMinimumSize() : null);
            return size.expand(container.getInsets().getWidth(), container.getInsets().getHeight());
        }

        private Dimension sumOf(Dimension box, Dimension label) {
            Dimension size = box.getCopy();
            if (label != null) {
                size.width += label.width + GAP;
                size.height = Math.max(size.height, label.height);
            }
            return size;
        }

        /** Natural box width with the memory-cell floor (see ValueBoxFigure doc). */
        private Dimension boxPreferred() {
            Dimension size = valueBox.getPreferredSize().getCopy();
            size.width = Math.max(size.width, ValueBoxFigure.MIN_WIDTH);
            return size;
        }

        @Override
        public void layout(IFigure container) {
            Rectangle client = container.getClientArea();
            int reserved = nameLabel != null ? nameLabel.getMinimumSize().width + GAP : 0;
            int boxWidth = Math.min(boxPreferred().width, Math.max(0, client.width - reserved));
            valueBox.setBounds(new Rectangle(client.right() - boxWidth, client.y, boxWidth, client.height));
            if (nameLabel != null) {
                int labelWidth = Math.min(nameLabel.getPreferredSize().width,
                        Math.max(0, client.width - boxWidth - GAP));
                nameLabel.setBounds(new Rectangle(client.right() - boxWidth - GAP - labelWidth,
                        client.y, labelWidth, client.height));
            }
        }
    }
}
