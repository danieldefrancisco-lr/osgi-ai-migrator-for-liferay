package io.github.danieldefrancisco_lr.migrator.preferences;

import io.github.danieldefrancisco_lr.migrator.Activator;
import io.github.danieldefrancisco_lr.migrator.core.agent.GeminiSettings;

/**
 * AI provider settings from the plugin preference store, with secrets in Eclipse Secure Storage.
 */
public class PreferenceGeminiSettings implements GeminiSettings {

    @Override
    public String get(String key) {
        return Activator.getDefault().getPreferenceStore().getString(key);
    }

    @Override
    public String getSecret(String key) {
        return SecureCredentials.get(key);
    }
}
