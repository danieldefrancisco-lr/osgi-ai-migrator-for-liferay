package io.github.danieldefrancisco_lr.migrator.preferences;

/**
 * Constant definitions for plug-in preferences.
 */
public class PreferenceConstants {

    // Auth Gateway Type
    public static final String P_AUTH_TYPE = "geminiAuthType";
    public static final String AUTH_TYPE_GCLOUD_ADC = "GCLOUD_ADC";
    public static final String AUTH_TYPE_VERTEX_AI = "VERTEX_AI";
    public static final String AUTH_TYPE_AI_STUDIO = "AI_STUDIO";

    // Google Cloud / Vertex AI Settings
    public static final String P_VERTEX_PROJECT_ID = "vertexProjectId";
    public static final String P_VERTEX_REGION = "vertexRegion";
    public static final String P_SERVICE_ACCOUNT_FILE = "vertexServiceAccountFile";

    // AI Studio (API Key) Settings
    public static final String P_GEMINI_API_KEY = "geminiApiKey";
    public static final String P_GEMINI_ENDPOINT = "geminiEndpoint";

    // Common Model Settings
    public static final String P_GEMINI_MODEL = "geminiModel";
    public static final String P_TEMPERATURE = "geminiTemperature";

    // Defaults
    public static final String DEFAULT_AUTH_TYPE = AUTH_TYPE_GCLOUD_ADC;
    public static final String DEFAULT_MODEL = "gemini-2.5-flash";
    public static final String DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com";
    public static final String DEFAULT_VERTEX_REGION = "us-central1";
    public static final String DEFAULT_TEMPERATURE = "0.2";
}
