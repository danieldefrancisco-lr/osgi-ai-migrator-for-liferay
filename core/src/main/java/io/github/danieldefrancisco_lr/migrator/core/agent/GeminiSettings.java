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

    /** Where the user changes these settings, used in error messages. */
    default String configurationHint() {
        return "Window -> Preferences -> OSGi AI Migrator for Liferay";
    }

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

    // AI provider (the interface keeps its historical name: Gemini was the only provider before 4.0)
    String P_PROVIDER = "llmProvider";
    String PROVIDER_GEMINI = "GEMINI";
    String PROVIDER_ANTHROPIC = "ANTHROPIC";
    /** Any server with an OpenAI-compatible Chat Completions API: OpenAI, Azure OpenAI, OpenRouter, Ollama, LM Studio... */
    String PROVIDER_OPENAI = "OPENAI";
    String DEFAULT_PROVIDER = PROVIDER_GEMINI;

    // Anthropic (Claude)
    String P_ANTHROPIC_API_KEY = "anthropicApiKey";
    String P_ANTHROPIC_MODEL = "anthropicModel";
    String P_ANTHROPIC_EFFORT = "anthropicEffort";
    String P_ANTHROPIC_ENDPOINT = "anthropicEndpoint";
    String DEFAULT_ANTHROPIC_MODEL = "claude-opus-5-5";
    String DEFAULT_ANTHROPIC_EFFORT = "high";
    String DEFAULT_ANTHROPIC_ENDPOINT = "https://api.anthropic.com";

    // OpenAI-compatible
    String P_OPENAI_API_KEY = "openaiApiKey";
    String P_OPENAI_MODEL = "openaiModel";
    String P_OPENAI_BASE_URL = "openaiBaseUrl";
    String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";

    // Defaults
    String DEFAULT_AUTH_TYPE = AUTH_TYPE_GCLOUD_ADC;
    String DEFAULT_MODEL = "gemini-2.5-flash";
    String DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com";
    String DEFAULT_VERTEX_REGION = "us-central1";
    String DEFAULT_TEMPERATURE = "0.2";
}
