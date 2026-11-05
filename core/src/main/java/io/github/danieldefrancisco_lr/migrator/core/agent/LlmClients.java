package io.github.danieldefrancisco_lr.migrator.core.agent;

/**
 * Creates the client of the AI provider chosen in the settings. Every IDE and the CLI create their client here, so a
 * new provider only needs a class and a case below.
 */
public final class LlmClients {

    private LlmClients() {
    }

    public static LlmClient create(GeminiSettings settings) {
        return switch (provider(settings)) {
            case GeminiSettings.PROVIDER_ANTHROPIC -> new AnthropicClient(settings);
            case GeminiSettings.PROVIDER_OPENAI -> new OpenAiClient(settings);
            default -> new GeminiClient(settings);
        };
    }

    /** The configured provider (GEMINI when unset), upper case. */
    public static String provider(GeminiSettings settings) {
        String p = settings.get(GeminiSettings.P_PROVIDER);
        return p == null || p.trim().isEmpty() ? GeminiSettings.DEFAULT_PROVIDER : p.trim().toUpperCase();
    }

    /** Provider and model, for progress messages ("Claude claude-opus-5-5"). */
    public static String describe(GeminiSettings settings) {
        String provider = provider(settings);
        switch (provider) {
            case GeminiSettings.PROVIDER_ANTHROPIC:
                return "Claude " + orDefault(settings.get(GeminiSettings.P_ANTHROPIC_MODEL), GeminiSettings.DEFAULT_ANTHROPIC_MODEL);
            case GeminiSettings.PROVIDER_OPENAI:
                return "OpenAI-compatible " + orDefault(settings.get(GeminiSettings.P_OPENAI_MODEL), "(no model set)") + " at " +
                    orDefault(settings.get(GeminiSettings.P_OPENAI_BASE_URL), GeminiSettings.DEFAULT_OPENAI_BASE_URL);
            default:
                return "Gemini " + orDefault(settings.get(GeminiSettings.P_GEMINI_MODEL), GeminiSettings.DEFAULT_MODEL) + " (" +
                    orDefault(settings.get(GeminiSettings.P_AUTH_TYPE), GeminiSettings.DEFAULT_AUTH_TYPE) + ")";
        }
    }

    static String orDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
