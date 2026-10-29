package io.github.danieldefrancisco_lr.migrator.agent;

import io.github.danieldefrancisco_lr.migrator.Activator;
import io.github.danieldefrancisco_lr.migrator.preferences.PreferenceConstants;
import io.github.danieldefrancisco_lr.migrator.preferences.SecureCredentials;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.eclipse.jface.preference.IPreferenceStore;

/**
 * Communicates with Gemini via Google Cloud ADC (gcloud), Vertex AI (Service Account), or Google AI Studio (API Key).
 */
public class GeminiClient {

    private final HttpClient httpClient;

    public GeminiClient() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    public String generateContent(String systemInstruction, String userPrompt) throws Exception {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        String authType = store.getString(PreferenceConstants.P_AUTH_TYPE);
        String model = store.getString(PreferenceConstants.P_GEMINI_MODEL);
        String temperatureStr = store.getString(PreferenceConstants.P_TEMPERATURE);

        if (model == null || model.trim().isEmpty()) {
            model = PreferenceConstants.DEFAULT_MODEL;
        }

        double temperature = 0.2;
        try {
            temperature = Double.parseDouble(temperatureStr);
        } catch (Exception ignored) {}

        String escapedPrompt = escapeJson(userPrompt);
        String escapedSystem = escapeJson(systemInstruction);

        String jsonPayload = "{\n" +
            "  \"systemInstruction\": {\n" +
            "    \"parts\": [ { \"text\": \"" + escapedSystem + "\" } ]\n" +
            "  },\n" +
            "  \"contents\": [\n" +
            "    { \"role\": \"user\", \"parts\": [ { \"text\": \"" + escapedPrompt + "\" } ] }\n" +
            "  ],\n" +
            "  \"generationConfig\": {\n" +
            "    \"temperature\": " + temperature + "\n" +
            "  }\n" +
            "}";

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .header("Content-Type", "application/json")
            .timeout(Duration.ofMinutes(6))
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8));

        // 1. Google Cloud ADC (gcloud CLI)
        if (PreferenceConstants.AUTH_TYPE_GCLOUD_ADC.equalsIgnoreCase(authType)) {
            String accessToken = GoogleAuthHelper.getGCloudAccessToken();

            String projectId = store.getString(PreferenceConstants.P_VERTEX_PROJECT_ID);
            if (projectId == null || projectId.trim().isEmpty()) {
                projectId = GoogleAuthHelper.getGCloudActiveProject();
            }
            if (projectId == null || projectId.trim().isEmpty()) {
                throw new IllegalStateException("GCP Project ID is not configured. Please set it in Window -> Preferences -> OSGi AI Migrator for Liferay, or run 'gcloud config set project <PROJECT_ID>'.");
            }

            String region = store.getString(PreferenceConstants.P_VERTEX_REGION);
            if (region == null || region.trim().isEmpty()) {
                region = PreferenceConstants.DEFAULT_VERTEX_REGION;
            }

            String vertexUrl = "https://" + region + "-aiplatform.googleapis.com/v1/projects/" +
                projectId + "/locations/" + region + "/publishers/google/models/" + model + ":generateContent";

            requestBuilder.uri(URI.create(vertexUrl));
            requestBuilder.header("Authorization", "Bearer " + accessToken);
        }
        // 2. Vertex AI via Service Account JSON File
        else if (PreferenceConstants.AUTH_TYPE_VERTEX_AI.equalsIgnoreCase(authType)) {
            String saPath = store.getString(PreferenceConstants.P_SERVICE_ACCOUNT_FILE);
            if (saPath == null || saPath.trim().isEmpty()) {
                throw new IllegalStateException("Vertex AI Service Account JSON file is not configured. Please set it in Window -> Preferences -> OSGi AI Migrator for Liferay.");
            }
            File saFile = new File(saPath);
            if (!saFile.exists()) {
                throw new IllegalStateException("Service Account JSON file does not exist: " + saPath);
            }

            GoogleAuthHelper.ServiceAccountInfo saInfo = GoogleAuthHelper.parseServiceAccount(saFile);
            String accessToken = GoogleAuthHelper.getServiceAccountAccessToken(saFile, httpClient);

            String region = store.getString(PreferenceConstants.P_VERTEX_REGION);
            if (region == null || region.trim().isEmpty()) {
                region = PreferenceConstants.DEFAULT_VERTEX_REGION;
            }

            String projectId = store.getString(PreferenceConstants.P_VERTEX_PROJECT_ID);
            if (projectId == null || projectId.trim().isEmpty()) {
                projectId = saInfo.projectId;
            }

            String vertexUrl = "https://" + region + "-aiplatform.googleapis.com/v1/projects/" +
                projectId + "/locations/" + region + "/publishers/google/models/" + model + ":generateContent";

            requestBuilder.uri(URI.create(vertexUrl));
            requestBuilder.header("Authorization", "Bearer " + accessToken);
        }
        // 3. Google AI Studio (API Key)
        else {
            String apiKey = SecureCredentials.get(PreferenceConstants.P_GEMINI_API_KEY);
            if (apiKey == null || apiKey.trim().isEmpty()) {
                throw new IllegalStateException("Gemini API Key is not configured. Please set it in Window -> Preferences -> OSGi AI Migrator for Liferay.");
            }

            String endpoint = store.getString(PreferenceConstants.P_GEMINI_ENDPOINT);
            if (endpoint == null || endpoint.trim().isEmpty()) {
                endpoint = PreferenceConstants.DEFAULT_ENDPOINT;
            }

            // Send the key as a header so it never appears in URLs, proxy logs or error messages
            String requestUrl = endpoint + "/v1beta/models/" + model + ":generateContent";
            requestBuilder.uri(URI.create(requestUrl));
            requestBuilder.header("x-goog-api-key", apiKey);
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new RuntimeException("Gemini Gateway Error (HTTP " + response.statusCode() + "): " + response.body());
        }

        return extractTextFromGeminiResponse(response.body());
    }

    private String extractTextFromGeminiResponse(String json) {
        int textIdx = json.indexOf("\"text\": \"");
        if (textIdx != -1) {
            int start = textIdx + 9;
            int end = start;
            boolean escaped = false;
            while (end < json.length()) {
                char c = json.charAt(end);
                if (c == '\\') {
                    escaped = !escaped;
                } else if (c == '\"' && !escaped) {
                    break;
                } else {
                    escaped = false;
                }
                end++;
            }
            String rawText = json.substring(start, end);
            return unescapeJson(rawText);
        }
        return json;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String unescapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < len) {
                char next = s.charAt(i + 1);
                if (next == 'u' && i + 5 < len) {
                    try {
                        String hex = s.substring(i + 2, i + 6);
                        int code = Integer.parseInt(hex, 16);
                        sb.append((char) code);
                        i += 5;
                        continue;
                    } catch (NumberFormatException ignored) {}
                } else if (next == 'n') {
                    sb.append('\n'); i++; continue;
                } else if (next == 'r') {
                    sb.append('\r'); i++; continue;
                } else if (next == 't') {
                    sb.append('\t'); i++; continue;
                } else if (next == '"') {
                    sb.append('"'); i++; continue;
                } else if (next == '\\') {
                    sb.append('\\'); i++; continue;
                } else if (next == '/') {
                    sb.append('/'); i++; continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
