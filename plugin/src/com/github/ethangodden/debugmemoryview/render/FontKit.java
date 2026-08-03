package com.github.ethangodden.debugmemoryview.render;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;

/** Fonts for the diagram, derived once from the JFace defaults via the shared ResourceManager. */
public final class FontKit {

    private final ResourceManager resources;

    private Font header;

    public FontKit(ResourceManager resources) {
        this.resources = resources;
    }

    /** Row/name font. */
    public Font name() {
        return JFaceResources.getDefaultFont();
    }

    /** Bold header font (frames, boxes, column titles). */
    public Font header() {
        if (header == null) {
            header = resources.create(JFaceResources.getDefaultFontDescriptor().setStyle(SWT.BOLD));
        }
        return header;
    }

    /** Monospace font (tooltip value lines). */
    public Font value() {
        return JFaceResources.getTextFont();
    }
}
