package com.github.ethangodden.diaram.render;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.FigureCanvas;
import org.eclipse.draw2d.Graphics;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.RangeModel;
import org.eclipse.draw2d.Viewport;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.swt.graphics.Color;

/**
 * Auto-hiding overlay scroll thumbs (macOS-editor style). Scrollbars everywhere
 * are NEVER; while an axis is actively scrolling this draws a slim rounded
 * semi-transparent thumb on a dedicated mouse-transparent layer along the
 * viewport's right edge (bottom edge for a horizontal axis), then fades it out
 * after a short idle. Thumbs are display-only — wheel and click-to-reveal remain
 * the scrolling affordances. Geometry comes from the axis RangeModel and the
 * viewport's absolute client area at show-time, so a live scroll re-positions
 * the thumb on every value change. Any {@link Viewport} can be tracked: the
 * per-column panes drive the vertical thumbs, the outer canvas viewport drives
 * the whole-view horizontal thumb.
 */
final class ScrollThumbOverlay {

    private static final int THICKNESS = 6;
    private static final int EDGE_INSET = 2; // off the pane's edge
    private static final int END_INSET = 3; // track inset at both ends
    private static final int MIN_LENGTH = 20;
    private static final int MAX_ALPHA = 120;
    private static final int IDLE_MILLIS = 600;
    private static final int FADE_STEP_MILLIS = 30;
    private static final int FADE_STEP_ALPHA = 24;

    private final FigureCanvas canvas;
    private final IFigure layer;
    private final Supplier<Color> color; // palette color, re-read each paint (theme switches)
    private final List<Thumb> thumbs = new ArrayList<>();

    ScrollThumbOverlay(FigureCanvas canvas, IFigure layer, Supplier<Color> color) {
        this.canvas = canvas;
        this.layer = layer;
        this.color = color;
    }

    /** Registers an axis: adds its thumb figure and re-shows on every RangeModel value change. */
    void track(Viewport viewport, boolean vertical) {
        Thumb thumb = new Thumb(viewport, vertical);
        thumbs.add(thumb);
        layer.add(thumb.figure);
        model(viewport, vertical).addPropertyChangeListener(event -> {
            if (RangeModel.PROPERTY_VALUE.equals(event.getPropertyName())) {
                thumb.show();
            }
        });
    }

    /** Explicit activity nudge (wheel gesture, click-to-reveal), even when the value clamped. */
    void show(Viewport viewport, boolean vertical) {
        for (Thumb thumb : thumbs) {
            if (thumb.viewport == viewport && thumb.vertical == vertical) {
                thumb.show();
            }
        }
    }

    /** Hides everything now and orphans in-flight timers (rebuild / clear / dispose). */
    void reset() {
        thumbs.forEach(Thumb::hideNow);
    }

    private static RangeModel model(Viewport viewport, boolean vertical) {
        return vertical ? viewport.getVerticalRangeModel() : viewport.getHorizontalRangeModel();
    }

    private final class Thumb {

        final Viewport viewport;
        final boolean vertical;
        final Figure figure = new ThumbFigure();
        int alpha;
        int generation; // bumped on every show/hide; stale timer chains see a mismatch and stop

        Thumb(Viewport viewport, boolean vertical) {
            this.viewport = viewport;
            this.vertical = vertical;
            figure.setVisible(false);
            figure.setEnabled(false); // display-only: never a mouse target
        }

        void show() {
            if (canvas.isDisposed()) {
                return;
            }
            Rectangle bounds = thumbBounds();
            if (bounds == null) {
                hideNow();
                return;
            }
            alpha = MAX_ALPHA;
            figure.setBounds(bounds); // damages old + new bounds itself
            figure.setVisible(true);
            figure.repaint(); // bounds can be unchanged mid-fade; still damage for the alpha jump
            int gen = ++generation;
            canvas.getDisplay().timerExec(IDLE_MILLIS, () -> fadeStep(gen));
        }

        void hideNow() {
            generation++;
            alpha = 0;
            figure.setVisible(false);
        }

        private void fadeStep(int gen) {
            if (canvas.isDisposed() || gen != generation) {
                return;
            }
            alpha -= FADE_STEP_ALPHA;
            if (alpha <= 0) {
                hideNow();
                return;
            }
            figure.repaint();
            canvas.getDisplay().timerExec(FADE_STEP_MILLIS, () -> fadeStep(gen));
        }

        /** Thumb bounds in layer coordinates; null when the axis has nothing to scroll. */
        private Rectangle thumbBounds() {
            RangeModel model = model(viewport, vertical);
            int range = model.getMaximum() - model.getMinimum();
            int extent = model.getExtent();
            if (extent <= 0 || range <= extent) {
                return null;
            }
            Rectangle visible = RevealUtil.absoluteClientArea(viewport);
            int track = (vertical ? visible.height : visible.width) - 2 * END_INSET;
            if (track <= MIN_LENGTH) {
                return null;
            }
            int length = Math.max(MIN_LENGTH, (int) ((long) track * extent / range));
            // DefaultRangeModel clamps value to [min, max - extent], so offset <= track - length.
            int offset = (int) ((long) (track - length) * (model.getValue() - model.getMinimum())
                    / (range - extent));
            Rectangle bounds = vertical
                    ? new Rectangle(visible.right() - EDGE_INSET - THICKNESS,
                            visible.y + END_INSET + offset, THICKNESS, length)
                    : new Rectangle(visible.x + END_INSET + offset,
                            visible.bottom() - EDGE_INSET - THICKNESS, length, THICKNESS);
            layer.translateToRelative(bounds);
            return bounds;
        }

        private final class ThumbFigure extends Figure {

            @Override
            protected void paintFigure(Graphics graphics) {
                graphics.setAlpha(alpha);
                graphics.setBackgroundColor(color.get());
                graphics.fillRoundRectangle(getBounds(), THICKNESS, THICKNESS);
            }
        }
    }
}
