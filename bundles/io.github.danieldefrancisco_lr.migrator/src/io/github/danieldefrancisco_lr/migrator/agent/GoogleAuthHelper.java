package io.github.danieldefrancisco_lr.migrator.agent;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Handles Google Cloud authentication via either local gcloud ADC (CLI) or Service Account JSON.
 */
public class GoogleAuthHelper {

    private static String cachedSaAccessToken = null;
    private static Instant saTokenExpiry = Instant.MIN;

    private static String cachedGCloudAccessToken = null;
    private static Instant gCloudTokenExpiry = Instant.MIN;

    // --- 1. gcloud CLI (Application Default Credentials) ---

    public static synchronized String getGCloudAccessToken() throws Exception {
        if (cachedGCloudAccessToken != null && Instant.now().isBefore(gCloudTokenExpiry.minusSeconds(60))) {
            return cachedGCloudAccessToken;
        }

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ProcessBuilder pb = isWindows
            ? new ProcessBuilder("cmd.exe", "/c", "gcloud auth print-access-token")
            : new ProcessBuilder("gcloud", "auth", "print-access-token");

        pb.redirectErrorStream(true);
        Process p = pb.start();

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }

        boolean finished = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new java.util.concurrent.TimeoutException("The 'gcloud' CLI command timed out after 15 seconds.\nPlease ensure 'gcloud auth login' is completed in your terminal.");
        }

        String output = sb.toString().trim();
        int exitCode = p.exitValue();

        if (exitCode != 0) {
            throw new RuntimeException("gcloud CLI authentication failed:\n" + output +
                "\nPlease open your terminal and run: gcloud auth login");
        }

        // Parse token (extract line starting with ya29. or the last non-empty line)
        String foundToken = null;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("ya29.")) {
                foundToken = trimmed;
                break;
            }
        }
        if (foundToken == null && !output.isEmpty()) {
            foundToken = output.split("\n")[output.split("\n").length - 1].trim();
        }

        if (foundToken == null || foundToken.contains(" ") || foundToken.contains("ERROR")) {
            throw new RuntimeException("Failed to obtain access token from gcloud:\n" + output +
                "\nPlease run 'gcloud auth login' in your terminal.");
        }

        cachedGCloudAccessToken = foundToken;
        gCloudTokenExpiry = Instant.now().plusSeconds(3000); // gcloud tokens last ~60 minutes

        return cachedGCloudAccessToken;
    }

    public static String getGCloudActiveProject() {
        try {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            ProcessBuilder pb = isWindows
                ? new ProcessBuilder("cmd.exe", "/c", "gcloud config get-value project")
                : new ProcessBuilder("gcloud", "config", "get-value", "project");

            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            p.waitFor();

            String output = sb.toString().trim();
            for (String line : output.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.contains(" ") && !trimmed.contains(":") && !trimmed.startsWith("ERROR")) {
                    return trimmed;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // --- 2. Service Account JSON Key (RS256 JWT Exchange) ---

    public static class ServiceAccountInfo {
        public String projectId;
        public String clientEmail;
        public String privateKey;
    }

    public static ServiceAccountInfo parseServiceAccount(File jsonFile) throws Exception {
        String json = Files.readString(jsonFile.toPath(), StandardCharsets.UTF_8);
        ServiceAccountInfo info = new ServiceAccountInfo();

        info.projectId = extractJsonStringField(json, "project_id");
        info.clientEmail = extractJsonStringField(json, "client_email");
        info.privateKey = extractJsonStringField(json, "private_key");

        if (info.clientEmail == null || info.privateKey == null) {
            throw new IllegalArgumentException("Invalid Service Account JSON: missing client_email or private_key.");
        }

        return info;
    }

    public static synchronized String getServiceAccountAccessToken(File serviceAccountJsonFile, HttpClient httpClient) throws Exception {
        if (cachedSaAccessToken != null && Instant.now().isBefore(saTokenExpiry.minusSeconds(60))) {
            return cachedSaAccessToken;
        }

        ServiceAccountInfo sa = parseServiceAccount(serviceAccountJsonFile);

        long now = Instant.now().getEpochSecond();
        long exp = now + 3600;

        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(header.getBytes(StandardCharsets.UTF_8));

        String claims = "{" +
            "\"iss\":\"" + sa.clientEmail + "\"," +
            "\"scope\":\"https://www.googleapis.com/auth/cloud-platform\"," +
            "\"aud\":\"https://oauth2.googleapis.com/token\"," +
            "\"exp\":" + exp + "," +
            "\"iat\":" + now +
            "}";
        String encodedClaims = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8));

        String signInput = encodedHeader + "." + encodedClaims;
        PrivateKey privateKey = parsePrivateKey(sa.privateKey);

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(signInput.getBytes(StandardCharsets.UTF_8));
        byte[] signedBytes = signature.sign();
        String encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signedBytes);

        String assertion = signInput + "." + encodedSignature;

        String formData = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8) +
            "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://oauth2.googleapis.com/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formData, StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(30))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to obtain OAuth2 token from Google (HTTP " + response.statusCode() + "): " + response.body());
        }

        String responseBody = response.body();
        String token = extractJsonStringField(responseBody, "access_token");
        if (token == null) {
            throw new RuntimeException("OAuth2 token not found in Google response: " + responseBody);
        }

        cachedSaAccessToken = token;
        saTokenExpiry = Instant.now().plusSeconds(3500);

        return cachedSaAccessToken;
    }

    private static PrivateKey parsePrivateKey(String pemKey) throws Exception {
        String cleanKey = pemKey
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(cleanKey);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    private static String extractJsonStringField(String json, String fieldName) {
        String key = "\"" + fieldName + "\":";
        int idx = json.indexOf(key);
        if (idx == -1) return null;

        int startQuote = json.indexOf("\"", idx + key.length());
        if (startQuote == -1) return null;

        int endQuote = startQuote + 1;
        boolean escaped = false;
        while (endQuote < json.length()) {
            char c = json.charAt(endQuote);
            if (c == '\\') {
                escaped = !escaped;
            } else if (c == '\"' && !escaped) {
                break;
            } else {
                escaped = false;
            }
            endQuote++;
        }

        if (endQuote >= json.length()) return null;

        String val = json.substring(startQuote + 1, endQuote);
        return val.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
