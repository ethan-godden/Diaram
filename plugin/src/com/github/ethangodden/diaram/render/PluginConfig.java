package com.github.ethangodden.diaram.render;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.ui.IMemento;

/**
 * Per-view render settings (persisted via the view memento) plus the SWT/JFace
 * theming resources ({@link ColorPalette}, {@link FontKit}) they configure.
 * Constructable with a no-arg constructor before the canvas exists (so
 * {@link #restore} can run from {@code IViewPart#init}); {@link #initRenderResources}
 * must run exactly once, after the canvas's {@link ResourceManager} exists,
 * before {@link #palette()}/{@link #fonts()} are used.
 */
public final class PluginConfig {

    public int maxHeapObjectsRendered = 200;
    public int maxFieldsPerObjectRendered = 16;
    public int maxArrayElementsRendered = 10;
    public int maxLocalsPerFrameRendered = 24;
    public int maxValueChars = 60;
    public boolean showStatics = true;
    public boolean highlightChanges = true;

    private @Nullable ColorPalette palette;
    private @Nullable FontKit fonts;

    /** Must be called exactly once, after the canvas's ResourceManager exists. */
    public void initRenderResources(ResourceManager resources) {
        if (palette != null) {
            throw new IllegalStateException("initRenderResources() already called"); //$NON-NLS-1$
        }
        palette = new ColorPalette(resources);
        fonts = new FontKit(resources);
    }

    public ColorPalette palette() {
        if (palette == null) {
            throw new IllegalStateException("initRenderResources() not called yet"); //$NON-NLS-1$
        }
        return palette;
    }

    public FontKit fonts() {
        if (fonts == null) {
            throw new IllegalStateException("initRenderResources() not called yet"); //$NON-NLS-1$
        }
        return fonts;
    }

    public void save(IMemento memento) {
        memento.putInteger("maxHeapObjects", maxHeapObjectsRendered);
        memento.putInteger("maxFields", maxFieldsPerObjectRendered);
        memento.putInteger("maxArrayElements", maxArrayElementsRendered);
        memento.putInteger("maxLocals", maxLocalsPerFrameRendered);
        memento.putInteger("maxValueChars", maxValueChars);
        memento.putBoolean("showStatics", showStatics);
        memento.putBoolean("highlightChanges", highlightChanges);
    }

    public void restore(@Nullable IMemento memento) {
        if (memento == null) {
            return;
        }
        maxHeapObjectsRendered = valueOr(memento.getInteger("maxHeapObjects"), maxHeapObjectsRendered);
        maxFieldsPerObjectRendered = valueOr(memento.getInteger("maxFields"), maxFieldsPerObjectRendered);
        maxArrayElementsRendered = valueOr(memento.getInteger("maxArrayElements"), maxArrayElementsRendered);
        maxLocalsPerFrameRendered = valueOr(memento.getInteger("maxLocals"), maxLocalsPerFrameRendered);
        maxValueChars = valueOr(memento.getInteger("maxValueChars"), maxValueChars);
        showStatics = boolValueOr(memento.getBoolean("showStatics"), showStatics);
        highlightChanges = boolValueOr(memento.getBoolean("highlightChanges"), highlightChanges);
    }

    private static int valueOr(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    private static boolean boolValueOr(Boolean value, boolean fallback) {
        return value != null ? value : fallback;
    }
}
