package io.github.danieldefrancisco_lr.migrator.preferences;

import io.github.danieldefrancisco_lr.migrator.Activator;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.preference.IPreferenceStore;

/**
 * Stores secrets (e.g. the Gemini API key) in Eclipse Secure Storage instead of the plain-text preference store.
 */
public class SecureCredentials {

    private static final String NODE = Activator.PLUGIN_ID;

    private SecureCredentials() {
    }

    public static String get(String key) {
        try {
            String value = node().get(key, null);
            if (value != null) {
                return value;
            }
        } catch (StorageException e) {
            Activator.getDefault().getLog().error("Unable to read '" + key + "' from Secure Storage", e);
            return "";
        }

        // Migrate a value saved in plain text by an earlier version, then remove it from the preference store.
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        String legacy = store.getString(key);
        if (!legacy.isEmpty()) {
            set(key, legacy);
            store.setToDefault(key);
        }
        return legacy;
    }

    public static void set(String key, String value) {
        try {
            ISecurePreferences node = node();
            if (value == null || value.isEmpty()) {
                node.remove(key);
            } else {
                node.put(key, value, true);
            }
            node.flush();
        } catch (Exception e) {
            Activator.getDefault().getLog().error("Unable to save '" + key + "' to Secure Storage", e);
        }
    }

    private static ISecurePreferences node() {
        return SecurePreferencesFactory.getDefault().node(NODE);
    }
}
