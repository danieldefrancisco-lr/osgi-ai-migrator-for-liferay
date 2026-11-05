package io.github.danieldefrancisco_lr.migrator.core.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Any server with an OpenAI-compatible Chat Completions API: OpenAI itself, Azure OpenAI, OpenRouter, or local servers
 * such as Ollama ({@code http://localhost:11434/v1}) and LM Studio ({@code http://localhost:1234/v1}).
 * <p>
 * Only the fields every implementation accepts are sent (model and messages): sampling and token limits differ
 * between servers and models, so they keep their own defaults. The API key is optional because local servers do not
 * need one.
 */
public class OpenAiClient implements LlmClient {

    private final GeminiSettings settings;
    private HttpClient httpClient;

    public OpenAiClient(GeminiSettings settings) {
        this.settings = settings;
    }

    @Override
    public String generateContent(String systemInstruction, String userPrompt) throws Exception {
        String model = settings.get(GeminiSettings.P_OPENAI_MODEL);
        if (model == null || model.trim().isEmpty()) {
            throw new IllegalStateException("The model of the OpenAI-compatible provider is not configured (for example gpt-5 or qwen3-coder). " +
                "Please set it in " + settings.configurationHint() + ".");
        }
        String baseUrl = LlmClients.orDefault(settings.get(GeminiSettings.P_OPENAI_BASE_URL), GeminiSettings.DEFAULT_OPENAI_BASE_URL);
        String apiKey = settings.getSecret(GeminiSettings.P_OPENAI_API_KEY);

        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create(stripSlash(baseUrl) + "/chat/completions"))
            .header("Content-Type", "application/json")
            // Local models can take many minutes for a whole migration answer
            .timeout(Duration.ofMinutes(20))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody(model.trim(), systemInstruction, userPrompt), StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            request.header("Authorization", "Bearer " + apiKey.trim());
        }

        HttpResponse<String> response = http().send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI-compatible API Error (HTTP " + response.statusCode() + "): " + AnthropicClient.errorMessage(response.body()));
        }
        return readResponse(response.body());
    }

    static String requestBody(String model, String system, String user) {
        return "{\"model\":" + Json.quote(model) +
            ",\"messages\":[{\"role\":\"system\",\"content\":" + Json.quote(system == null ? "" : system) + "}" +
            ",{\"role\":\"user\",\"content\":" + Json.quote(user == null ? "" : user) + "}]}";
    }

    /** The text of the first choice. Fails on a cut answer (finish_reason length) and on a filtered one. */
    static String readResponse(String body) {
        Object json = Json.parse(body);
        Object finish = Json.path(json, "choices", 0, "finish_reason");
        if ("length".equals(finish)) {
            throw new RuntimeException("The model stopped at its output token limit (finish_reason length), so the answer is incomplete. " +
                "Use a model with a larger output limit, or raise the server's limit (for example num_ctx/num_predict in Ollama).");
        }
        if ("content_filter".equals(finish)) {
            throw new RuntimeException("The provider's content filter blocked the answer (finish_reason content_filter).");
        }
        Object content = Json.path(json, "choices", 0, "message", "content");
        if (content == null) {
            Object refusal = Json.path(json, "choices", 0, "message", "refusal");
            throw new RuntimeException(refusal != null ? "The model declined this request: " + refusal
                : "The OpenAI-compatible response has no message content: " + body);
        }
        return content.toString();
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Created on first use, so creating a client (when settings change or in tests) opens no connection. */
    private synchronized HttpClient http() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        }
        return httpClient;
    }
}
