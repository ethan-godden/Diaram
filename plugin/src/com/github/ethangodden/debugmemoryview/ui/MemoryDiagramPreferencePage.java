package com.github.ethangodden.debugmemoryview.ui;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ColorFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.github.ethangodden.debugmemoryview.Activator;
import com.github.ethangodden.debugmemoryview.PreferenceConstants;

/** Preferences > Memory Diagram: change highlighting toggle and accent colors. */
public class MemoryDiagramPreferencePage extends FieldEditorPreferencePage
        implements IWorkbenchPreferencePage {

    public MemoryDiagramPreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Change highlighting in the Memory Diagram view. "
                + "\"Restore Defaults\" returns the colors to theme-aware built-ins.");
    }

    @Override
    public void init(IWorkbench workbench) {
    }

    @Override
    protected void createFieldEditors() {
        addField(new BooleanFieldEditor(PreferenceConstants.PREF_HIGHLIGHT_CHANGES,
                "Highlight changes between suspends", getFieldEditorParent()));
        addField(new ColorFieldEditor(PreferenceConstants.PREF_COLOR_NEW,
                "New:", getFieldEditorParent()));
        addField(new ColorFieldEditor(PreferenceConstants.PREF_COLOR_UPDATED,
                "Updated:", getFieldEditorParent()));
    }
}
