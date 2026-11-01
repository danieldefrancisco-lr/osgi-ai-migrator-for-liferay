package io.github.danieldefrancisco_lr.migrator.intellij;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.util.PropertiesComponent;

import io.github.danieldefrancisco_lr.migrator.core.agent.GeminiSettings;

import java.util.Map;

/**
 * Gemini settings stored by the IDE: plain values in the application properties, the API key in the IDE's
 * password safe (the OS keychain where available). Same keys and defaults as the Eclipse plugin.
 */
public final class IdeGeminiSettings implements GeminiSettings {

    private static final String PREFIX = "io.github.danieldefrancisco_lr.migrator.";

    private static final Map<String, String> DEFAULTS = Map.of(
        P_AUTH_TYPE, DEFAULT_AUTH_TYPE,
        P_GEMINI_MODEL, DEFAULT_MODEL,
        P_TEMPERATURE, DEFAULT_TEMPERATURE,
        P_VERTEX_REGION, DEFAULT_VERTEX_REGION,
        P_GEMINI_ENDPOINT, DEFAULT_ENDPOINT);

    @Override
    public String get(String key) {
        return PropertiesComponent.getInstance().getValue(PREFIX + key, DEFAULTS.getOrDefault(key, ""));
    }

    public void set(String key, String value) {
        PropertiesComponent.getInstance().setValue(PREFIX + key, value, DEFAULTS.getOrDefault(key, ""));
    }

    @Override
    public String getSecret(String key) {
        return PasswordSafe.getInstance().getPassword(credentials(key));
    }

    public void setSecret(String key, String value) {
        PasswordSafe.getInstance().setPassword(credentials(key), value == null || value.isEmpty() ? null : value);
    }

    @Override
    public String configurationHint() {
        return "Settings -> Tools -> OSGi AI Migrator for Liferay";
    }

    private static CredentialAttributes credentials(String key) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName("OSGi AI Migrator for Liferay", key));
    }
}
