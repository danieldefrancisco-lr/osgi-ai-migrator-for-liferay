package io.github.danieldefrancisco_lr.migrator.core.agent;

/**
 * Gemini connection settings, read on every request so changes apply immediately.
 * Each IDE stores them its own way (Eclipse: preference store and secure storage).
 */
public interface GeminiSettings {

    /** Plain setting, or null/empty when not set. */
    String get(String key);

    /** Secret setting such as the API key, or null/empty when not set. */
    String getSecret(String key);

    // Auth Gateway Type
    String P_AUTH_TYPE = "geminiAuthType";
    String AUTH_TYPE_GCLOUD_ADC = "GCLOUD_ADC";
    String AUTH_TYPE_VERTEX_AI = "VERTEX_AI";
    String AUTH_TYPE_AI_STUDIO = "AI_STUDIO";

    // Google Cloud / Vertex AI Settings
    String P_VERTEX_PROJECT_ID = "vertexProjectId";
    String P_VERTEX_REGION = "vertexRegion";
    String P_SERVICE_ACCOUNT_FILE = "vertexServiceAccountFile";

    // AI Studio (API Key) Settings
    String P_GEMINI_API_KEY = "geminiApiKey";
    String P_GEMINI_ENDPOINT = "geminiEndpoint";

    // Common Model Settings
    String P_GEMINI_MODEL = "geminiModel";
    String P_TEMPERATURE = "geminiTemperature";

    // Defaults
    String DEFAULT_AUTH_TYPE = AUTH_TYPE_GCLOUD_ADC;
    String DEFAULT_MODEL = "gemini-2.5-flash";
    String DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com";
    String DEFAULT_VERTEX_REGION = "us-central1";
    String DEFAULT_TEMPERATURE = "0.2";
}
