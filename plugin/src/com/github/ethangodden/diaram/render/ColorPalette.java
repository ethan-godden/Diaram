package com.github.ethangodden.diaram.render;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;

import com.github.ethangodden.diaram.Activator;
import com.github.ethangodden.diaram.PreferenceConstants;
import com.github.ethangodden.diaram.model.diff.ChangeStatus;

/**
 * Theme-aware status colors. Dark mode is detected from the luminance of the
 * CSS-styled workbench control's background (not the OS theme). The two
 * change accents honor user overrides from the preference store: a key still
 * at its default uses the built-in theme-appropriate RGB, otherwise the user
 * RGB applies in both themes. All colors come from the shared ResourceManager
 * (deduplicated, disposed with the canvas).
 */
public final class ColorPalette {

    private final ResourceManager resources;

    private boolean dark;
    private boolean highlighting = true;

    private Color newFg;
    private Color newTint;
    private Color updatedFg;
    private Color updatedTint;
    private Color mutedFg;
    private Color unchangedFg;
    private Color unchangedConnection;
    private Color boxBg;
    private Color headerBg;
    private Color boxBorder;
    private Color hoverAccent;
    private Color hoverRowBg;
    private Color columnBg;

    public ColorPalette(ResourceManager resources) {
        this.resources = resources;
    }

    /** Re-reads theme + preference overrides; call at the start of every rebuild (UI thread). */
    public void refresh(Control themeControl, boolean highlightChanges) {
        RGB background = themeControl.getBackground().getRGB();
        dark = luminance(background) < 128;

        IPreferenceStore store = preferenceStore();
        highlighting = highlightChanges
                && (store == null || store.getBoolean(PreferenceConstants.PREF_HIGHLIGHT_CHANGES));

        newFg = color(prefOrBuiltIn(store, PreferenceConstants.PREF_COLOR_NEW,
                rgb(25, 128, 56), rgb(110, 200, 130)));
        updatedFg = color(prefOrBuiltIn(store, PreferenceConstants.PREF_COLOR_UPDATED,
                rgb(191, 102, 0), rgb(255, 178, 88)));

        mutedFg = color(dark ? rgb(140, 140, 140) : rgb(128, 128, 128));
        newTint = color(dark ? rgb(26, 58, 36) : rgb(223, 244, 223));
        updatedTint = color(dark ? rgb(74, 52, 20) : rgb(255, 236, 209));
        unchangedFg = color(dark ? rgb(220, 220, 220) : rgb(30, 30, 30));
        unchangedConnection = color(dark ? rgb(150, 150, 150) : rgb(100, 100, 100));
        boxBg = color(dark ? rgb(45, 45, 45) : rgb(252, 252, 252));
        headerBg = color(dark ? rgb(58, 62, 70) : rgb(234, 238, 244));
        boxBorder = color(dark ? rgb(110, 110, 110) : rgb(120, 120, 120));
        hoverAccent = color(dark ? rgb(95, 167, 255) : rgb(0, 102, 204));
        hoverRowBg = color(dark ? rgb(32, 56, 90) : rgb(214, 232, 255));
        columnBg = color(background);
    }

    /** Status actually rendered: everything UNCHANGED while highlighting is off. */
    public ChangeStatus effective(ChangeStatus status) {
        return highlighting ? status : ChangeStatus.UNCHANGED;
    }

    public Color statusForeground(ChangeStatus status) {
        return switch (status) {
            case NEW -> newFg;
            case UPDATED -> updatedFg;
            case UNCHANGED -> unchangedFg;
        };
    }

    /** Row background tint; null for statuses without one. */
    public @Nullable Color rowTint(ChangeStatus status) {
        return switch (status) {
            case NEW -> newTint;
            case UPDATED -> updatedTint;
            default -> null;
        };
    }

    /** Stripe accent for the row border; null (no stripe) for UNCHANGED. */
    public @Nullable Color stripe(ChangeStatus status) {
        return status == ChangeStatus.UNCHANGED ? null : statusForeground(status);
    }

    public Color connectionColor(ChangeStatus status) {
        return status == ChangeStatus.UNCHANGED ? unchangedConnection : statusForeground(status);
    }

    public Color textForeground() {
        return unchangedFg;
    }

    /** Muted gray for auxiliary text ("+N more…" expanders, info rows) — not a status accent. */
    public Color mutedForeground() {
        return mutedFg;
    }

    public Color boxBackground() {
        return boxBg;
    }

    public Color headerBackground() {
        return headerBg;
    }

    public Color boxBorder() {
        return boxBorder;
    }

    public Color hoverAccent() {
        return hoverAccent;
    }

    public Color hoverRowBackground() {
        return hoverRowBg;
    }

    public Color columnBackground() {
        return columnBg;
    }

    private RGB prefOrBuiltIn(IPreferenceStore store, String key, RGB light, RGB darkRgb) {
        RGB builtIn = dark ? darkRgb : light;
        if (store == null || store.isDefault(key)) {
            return builtIn;
        }
        return PreferenceConverter.getColor(store, key);
    }

    private static IPreferenceStore preferenceStore() {
        Activator activator = Activator.getDefault();
        return activator != null ? activator.getPreferenceStore() : null;
    }

    private Color color(RGB rgb) {
        return resources.createColor(rgb);
    }

    private static RGB rgb(int r, int g, int b) {
        return new RGB(r, g, b);
    }

    private static double luminance(RGB rgb) {
        return 0.299 * rgb.red + 0.587 * rgb.green + 0.114 * rgb.blue;
    }
}
