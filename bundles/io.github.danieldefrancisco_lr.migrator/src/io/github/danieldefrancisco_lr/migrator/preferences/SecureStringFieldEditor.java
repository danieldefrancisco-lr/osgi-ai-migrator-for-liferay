package io.github.danieldefrancisco_lr.migrator.preferences;

import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.widgets.Composite;

/**
 * Masked string field editor backed by {@link SecureCredentials} instead of the preference store.
 */
public class SecureStringFieldEditor extends StringFieldEditor {

    public SecureStringFieldEditor(String name, String labelText, Composite parent) {
        super(name, labelText, parent);
        getTextControl(parent).setEchoChar('*');
    }

    @Override
    protected void doLoad() {
        if (getTextControl() != null) {
            getTextControl().setText(SecureCredentials.get(getPreferenceName()));
        }
    }

    @Override
    protected void doLoadDefault() {
        if (getTextControl() != null) {
            getTextControl().setText("");
        }
        valueChanged();
    }

    @Override
    protected void doStore() {
        SecureCredentials.set(getPreferenceName(), getStringValue());
    }
}
