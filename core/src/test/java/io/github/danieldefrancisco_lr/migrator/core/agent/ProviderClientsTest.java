package io.github.danieldefrancisco_lr.migrator.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Request bodies and response parsing of the Claude and OpenAI-compatible clients, without network calls. */
public class ProviderClientsTest {

    @Test
    public void jsonRoundTripsTheCharactersOfSourceCode() {
        String code = "line \"one\"\n\tback\\slash é \u0001 </script>";
        assertEquals(code, Json.parse(Json.quote(code)));
        Object value = Json.parse("{\"a\": [1, 2.5, true, null, {\"b\": \"\\u00e9\\n\"}], \"c\": -3e2}");
        assertEquals(2.5, Json.path(value, "a", 1));
        assertEquals("é\n", Json.path(value, "a", 4, "b"));
        assertEquals(-300.0, Json.path(value, "c"));
        assertEquals(null, Json.path(value, "a", 9));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": 1} trailing"));
    }

    @Test
    public void claudeRequestHasNoSamplingSettingsAndOptsIntoFallbacks() {
        String body = AnthropicClient.requestBody("claude-opus-5-5", "high", true, "You are \"Leonardo\"", "Migrate\nthis");
        Object json = Json.parse(body);
        assertEquals("claude-opus-5-5", Json.path(json, "model"));
        assertEquals(Boolean.TRUE, Json.path(json, "stream"));
        assertEquals("You are \"Leonardo\"", Json.path(json, "system"));
        assertEquals("Migrate\nthis", Json.path(json, "messages", 0, "content"));
        assertEquals("high", Json.path(json, "output_config", "effort"));
        assertEquals("default", Json.path(json, "fallbacks"));
        assertFalse(body.contains("temperature"), "Claude Opus 5.5 rejects temperature");
        assertFalse(body.contains("thinking"), "thinking is always on: the field is omitted");

        assertTrue(AnthropicClient.supportsDefaultFallbacks("claude-opus-5-5"));
        assertTrue(AnthropicClient.supportsDefaultFallbacks("claude-sonnet-5-5"));
        assertFalse(AnthropicClient.supportsDefaultFallbacks("claude-haiku-5-5"));
        assertFalse(Json.parse(AnthropicClient.requestBody("claude-haiku-5-5", "high", false, "s", "u")).toString().contains("fallbacks"));
    }

    @Test
    public void claudeStreamKeepsOnlyTheText() {
        List<String> sse = List.of(
            "event: message_start",
            "data: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-opus-5-5\",\"content\":[]}}",
            "",
            "event: content_block_start",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hidden\"}}",
            "data: {\"type\":\"content_block_stop\",\"index\":0}",
            "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"### FILE: a.json\\n\"}}",
            "event: ping",
            "data: {\"type\":\"ping\"}",
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"{\\\"x\\\": 1}\"}}",
            "data: {\"type\":\"content_block_stop\",\"index\":1}",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":42}}",
            "data: {\"type\":\"message_stop\"}");
        assertEquals("### FILE: a.json\n{\"x\": 1}", AnthropicClient.readStream(sse.iterator()));
    }

    @Test
    public void claudeStreamFailsOnRefusalCutAnswerAndErrors() {
        RuntimeException refusal = assertThrows(RuntimeException.class, () -> AnthropicClient.readStream(List.of(
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"}}",
            "data: {\"type\":\"message_stop\"}").iterator()));
        assertTrue(refusal.getMessage().contains("declined"), refusal.getMessage());

        RuntimeException cut = assertThrows(RuntimeException.class, () -> AnthropicClient.readStream(List.of(
            "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"partial\"}}",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}",
            "data: {\"type\":\"message_stop\"}").iterator()));
        assertTrue(cut.getMessage().contains("output token limit"), cut.getMessage());

        RuntimeException overloaded = assertThrows(RuntimeException.class, () -> AnthropicClient.readStream(List.of(
            "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}").iterator()));
        assertTrue(overloaded.getMessage().contains("overloaded_error: Overloaded"), overloaded.getMessage());

        assertThrows(RuntimeException.class, () -> AnthropicClient.readStream(List.of(
            "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"partial\"}}").iterator()),
            "a stream closed before message_stop is incomplete");
    }

    @Test
    public void claudeNeedsAnApiKey() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> new AnthropicClient(settings(Map.of())).generateContent("s", "u"));
        assertTrue(e.getMessage().contains("Anthropic API Key"), e.getMessage());
    }

    @Test
    public void openAiResponseParsing() {
        Object request = Json.parse(OpenAiClient.requestBody("gpt-5", "system text", "user text"));
        assertEquals("system", Json.path(request, "messages", 0, "role"));
        assertEquals("user text", Json.path(request, "messages", 1, "content"));

        assertEquals("answer \"ok\"", OpenAiClient.readResponse(
            "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"answer \\\"ok\\\"\"},\"finish_reason\":\"stop\"}]}"));
        RuntimeException cut = assertThrows(RuntimeException.class, () -> OpenAiClient.readResponse(
            "{\"choices\":[{\"message\":{\"content\":\"part\"},\"finish_reason\":\"length\"}]}"));
        assertTrue(cut.getMessage().contains("output token limit"), cut.getMessage());
        assertEquals("PERMISSION_DENIED: blocked", AnthropicClient.errorMessage(
            "[{\"error\": {\"code\": 403, \"message\": \"blocked\", \"status\": \"PERMISSION_DENIED\"}}]"));
        IllegalStateException noModel = assertThrows(IllegalStateException.class,
            () -> new OpenAiClient(settings(Map.of())).generateContent("s", "u"));
        assertTrue(noModel.getMessage().contains("model"), noModel.getMessage());
    }

    @Test
    public void providerSelection() {
        assertTrue(LlmClients.create(settings(Map.of())) instanceof GeminiClient, "Gemini stays the default");
        assertTrue(LlmClients.create(settings(Map.of(GeminiSettings.P_PROVIDER, "anthropic"))) instanceof AnthropicClient);
        assertTrue(LlmClients.create(settings(Map.of(GeminiSettings.P_PROVIDER, "OPENAI"))) instanceof OpenAiClient);
        assertEquals("Claude claude-opus-5-5", LlmClients.describe(settings(Map.of(GeminiSettings.P_PROVIDER, "ANTHROPIC"))));
        assertEquals("OpenAI-compatible llama3 at http://localhost:11434/v1", LlmClients.describe(settings(Map.of(
            GeminiSettings.P_PROVIDER, "OPENAI", GeminiSettings.P_OPENAI_MODEL, "llama3", GeminiSettings.P_OPENAI_BASE_URL, "http://localhost:11434/v1"))));
    }

    private static GeminiSettings settings(Map<String, String> values) {
        Map<String, String> copy = new HashMap<>(values);
        return new GeminiSettings() {
            @Override
            public String get(String key) {
                return copy.get(key);
            }

            @Override
            public String getSecret(String key) {
                return copy.get(key);
            }
        };
    }
}
