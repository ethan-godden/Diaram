package com.github.ethangodden.diaram.ui;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.swt.graphics.RGB;

import com.github.ethangodden.diaram.Activator;
import com.github.ethangodden.diaram.PreferenceConstants;

/**
 * Default values for the memory diagram preferences. The color defaults are the
 * LIGHT-theme accents; while a color key is still at its default the palette
 * substitutes the built-in dark-theme variant under a dark workbench theme.
 */
public class PreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        store.setDefault(PreferenceConstants.PREF_HIGHLIGHT_CHANGES, true);
        PreferenceConverter.setDefault(store, PreferenceConstants.PREF_COLOR_NEW, new RGB(25, 128, 56));
        PreferenceConverter.setDefault(store, PreferenceConstants.PREF_COLOR_UPDATED, new RGB(191, 102, 0));
    }
}
