package com.github.ethangodden.debugmemoryview.render;

import org.eclipse.draw2d.Cursors;
import org.eclipse.draw2d.IFigure;
import org.eclipse.draw2d.MouseEvent;
import org.eclipse.draw2d.MouseListener;
import org.eclipse.draw2d.MouseMotionListener;

import com.github.ethangodden.debugmemoryview.render.figures.HeapObjectFigure;
import com.github.ethangodden.debugmemoryview.render.figures.VariableRowFigure;

/**
 * Single-slot hover state machine for variable rows: every row (primitive,
 * null, unreadable) gets the blue row tint; reference rows additionally
 * get connection thicken/recolor + target box outline + lazy preview tooltip,
 * and click reveals the target in the heap pane. Draw2d guarantees
 * mouseExited(old) before mouseEntered(new), so one slot suffices. reset() is
 * called before every rebuild; stale exits are ignored by identity check.
 */
public final class HoverController {

    private final DiagramController controller;
    private VariableRowFigure current;
    private StateConnection currentConnection;
    private HeapObjectFigure currentTarget;

    // One shared listener pair for every row; the row is the event source.
    private final MouseMotionListener motionListener = new MouseMotionListener.Stub() {
        @Override
        public void mouseEntered(MouseEvent me) {
            if (me.getSource() instanceof VariableRowFigure row) {
                enter(row);
            }
        }

        @Override
        public void mouseExited(MouseEvent me) {
            if (me.getSource() instanceof VariableRowFigure row) {
                exit(row);
            }
        }
    };

    private final MouseListener clickListener = new MouseListener.Stub() {
        @Override
        public void mousePressed(MouseEvent me) {
            if (me.button == 1 && me.getSource() instanceof VariableRowFigure row) {
                me.consume();
                controller.revealTarget(row);
            }
        }
    };

    HoverController(DiagramController controller) {
        this.controller = controller;
    }

    /**
     * Registers hover behavior on any variable row; reference rows
     * (targetId != null) additionally get click-to-reveal and the hand cursor.
     */
    void hookRow(VariableRowFigure row) {
        row.addMouseMotionListener(motionListener);
        if (row.targetToken() != null) {
            row.addMouseListener(clickListener);
            row.setCursor(Cursors.HAND);
        }
    }

    void enter(VariableRowFigure row) {
        if (current == row) {
            return;
        }
        reset();
        current = row;
        ColorPalette palette = controller.palette();
        row.setHoverHighlight(true, palette);

        currentConnection = controller.connectionFor(row);
        if (currentConnection != null) {
            currentConnection.setHover(true, palette);
            controller.raiseConnection(currentConnection); // paint over sibling arrows
        }
        if (row.targetToken() != null) {
            currentTarget = controller.objectFigureFor(row.targetToken());
            if (currentTarget != null) {
                currentTarget.setHoverHighlight(true);
            }
        }
        if (row.getToolTip() == null) {
            IFigure preview = controller.buildPreview(row);
            if (preview != null) {
                row.setToolTip(preview); // built lazily once; ToolTipHelper shows it in place
            }
        }
    }

    void exit(VariableRowFigure row) {
        if (current != row) {
            return; // stale exit after a rebuild
        }
        reset();
    }

    /** Clears the slot and restores visuals; safe on figures about to be discarded. */
    public void reset() {
        if (current != null) {
            current.setHoverHighlight(false, controller.palette());
        }
        if (currentConnection != null) {
            currentConnection.setHover(false, controller.palette());
        }
        if (currentTarget != null) {
            currentTarget.setHoverHighlight(false);
        }
        current = null;
        currentConnection = null;
        currentTarget = null;
    }

    /** True while the hover slot points at this heap box (used by the reveal flash). */
    public boolean isCurrentTarget(HeapObjectFigure figure) {
        return currentTarget == figure;
    }
}
