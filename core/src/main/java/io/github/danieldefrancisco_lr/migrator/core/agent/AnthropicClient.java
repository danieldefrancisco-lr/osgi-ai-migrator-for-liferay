package io.github.danieldefrancisco_lr.migrator.core.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Claude through the Anthropic Messages API, over plain HTTP (the core has no dependencies, see {@link Json}).
 * <p>
 * The answer is streamed (server-sent events): a migration answer is long and a non-streamed request of that size can
 * hit HTTP timeouts. Only the text deltas are kept; thinking blocks are skipped.
 * <p>
 * For the models that support it, the request opts into server-side refusal fallbacks ({@code fallbacks: "default"}):
 * when a safety classifier declines the request (legacy code with security-related names can trigger a false
 * positive), Anthropic retries it on the recommended fallback model in the same stream instead of failing.
 */
public class AnthropicClient implements LlmClient {

    static final String API_VERSION = "2026-10-21";
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    static final int MAX_TOKENS = 64000;

    private final GeminiSettings settings;
    private HttpClient httpClient;

    public AnthropicClient(GeminiSettings settings) {
        this.settings = settings;
    }

    @Override
    public String generateContent(String systemInstruction, String userPrompt) throws Exception {
        String apiKey = settings.getSecret(GeminiSettings.P_ANTHROPIC_API_KEY);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("Anthropic API Key is not configured. Please set it in " + settings.configurationHint() + ".");
        }
        String model = LlmClients.orDefault(settings.get(GeminiSettings.P_ANTHROPIC_MODEL), GeminiSettings.DEFAULT_ANTHROPIC_MODEL);
        String effort = LlmClients.orDefault(settings.get(GeminiSettings.P_ANTHROPIC_EFFORT), GeminiSettings.DEFAULT_ANTHROPIC_EFFORT);
        String endpoint = LlmClients.orDefault(settings.get(GeminiSettings.P_ANTHROPIC_ENDPOINT), GeminiSettings.DEFAULT_ANTHROPIC_ENDPOINT);
        boolean fallbacks = supportsDefaultFallbacks(model);

        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create(stripSlash(endpoint) + "/v1/messages"))
            .header("content-type", "application/json")
            .header("x-api-key", apiKey.trim())
            .header("anthropic-version", API_VERSION)
            .timeout(Duration.ofMinutes(10))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody(model, effort, fallbacks, systemInstruction, userPrompt), StandardCharsets.UTF_8));
        if (fallbacks) {
            request.header("anthropic-beta", FALLBACK_BETA);
        }

        HttpResponse<Stream<String>> response = http().send(request.build(), HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() != 200) {
                throw new RuntimeException("Anthropic API Error (HTTP " + response.statusCode() + "): " + errorMessage(lines.collect(Collectors.joining("\n"))));
            }
            return readStream(lines.iterator());
        }
    }

    /** Request body. Opus 5.5 and later always think and reject temperature, so no sampling settings are sent. */
    static String requestBody(String model, String effort, boolean fallbacks, String system, String user) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"model\":").append(Json.quote(model));
        sb.append(",\"max_tokens\":").append(MAX_TOKENS);
        sb.append(",\"stream\":true");
        sb.append(",\"system\":").append(Json.quote(system == null ? "" : system));
        sb.append(",\"messages\":[{\"role\":\"user\",\"content\":").append(Json.quote(user == null ? "" : user)).append("}]");
        sb.append(",\"output_config\":{\"effort\":").append(Json.quote(effort)).append("}");
        if (fallbacks) {
            sb.append(",\"fallbacks\":\"default\"");
        }
        return sb.append("}").toString();
    }

    /** Models with server-side default fallbacks on the Claude API. */
    static boolean supportsDefaultFallbacks(String model) {
        String m = model.toLowerCase();
        return m.startsWith("claude-opus-5") || m.startsWith("claude-fable-5") || m.startsWith("claude-sonnet-5-5");
    }

    /**
     * Reads the server-sent events of a streamed Messages response and returns the text. Fails on a refusal, on a cut
     * answer (max_tokens) and on error events.
     */
    static String readStream(Iterator<String> lines) {
        StringBuilder text = new StringBuilder();
        String stopReason = null;
        boolean stopped = false;
        while (lines.hasNext()) {
            String line = lines.next();
            if (!line.startsWith("data:")) {
                continue; // "event:" lines repeat the type that the data carries; blank lines separate events
            }
            String data = line.substring(5).trim();
            if (data.isEmpty()) {
                continue;
            }
            Object event = Json.parse(data);
            String type = String.valueOf(Json.path(event, "type"));
            switch (type) {
                case "content_block_delta":
                    if ("text_delta".equals(Json.path(event, "delta", "type"))) {
                        Object t = Json.path(event, "delta", "text");
                        if (t != null) {
                            text.append(t);
                        }
                    }
                    break;
                case "message_delta":
                    Object reason = Json.path(event, "delta", "stop_reason");
                    if (reason != null) {
                        stopReason = reason.toString();
                    }
                    break;
                case "message_stop":
                    stopped = true;
                    break;
                case "error":
                    throw new RuntimeException("Anthropic API Error during the answer: " + errorMessage(data));
                default:
                    break;
            }
        }
        if ("refusal".equals(stopReason)) {
            throw new RuntimeException("Claude declined this request (stop_reason refusal), so there is no answer to use. " +
                "Run the migration again, or try another Claude model or provider.");
        }
        if ("max_tokens".equals(stopReason)) {
            throw new RuntimeException("Claude stopped at its output token limit (max_tokens " + MAX_TOKENS + "), so the answer is incomplete. " +
                "Migrate the module in smaller parts, or lower the effort setting.");
        }
        if (!stopped) {
            throw new RuntimeException("The connection to the Anthropic API closed before the answer was complete.");
        }
        return text.toString();
    }

    /** The "message" of an API error body, or the body itself. */
    static String errorMessage(String body) {
        try {
            Object json = Json.parse(body);
            if (json instanceof java.util.List) {
                json = Json.path(json, 0); // Google's OpenAI-compatible endpoints wrap the error in an array
            }
            Object type = Json.path(json, "error", "type");
            if (type == null) {
                type = Json.path(json, "error", "status");
            }
            Object message = Json.path(json, "error", "message");
            if (message != null) {
                return (type != null ? type + ": " : "") + message;
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return body;
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
