package io.github.danieldefrancisco_lr.migrator.cli;

import io.github.danieldefrancisco_lr.migrator.core.agent.GeminiSettings;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Gemini settings for the CLI, resolved per key from (highest first): command-line options, environment variables,
 * the config file (~/.osgi-ai-migrator/config.properties) and defaults. The API key is never taken from the command
 * line, so it does not end up in the shell history.
 */
public class CliSettings implements GeminiSettings {

    public static final Path DEFAULT_CONFIG_FILE = Path.of(System.getProperty("user.home"), ".osgi-ai-migrator", "config.properties");

    /** Standard Google environment variables mapped to settings keys. */
    static final Map<String, String> ENV_VARS = new LinkedHashMap<>();
    static {
        ENV_VARS.put(P_GEMINI_API_KEY, "GEMINI_API_KEY");
        ENV_VARS.put(P_SERVICE_ACCOUNT_FILE, "GOOGLE_APPLICATION_CREDENTIALS");
        ENV_VARS.put(P_VERTEX_PROJECT_ID, "GOOGLE_CLOUD_PROJECT");
        ENV_VARS.put(P_VERTEX_REGION, "GOOGLE_CLOUD_LOCATION");
    }

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final Path configFile;

    public CliSettings(Map<String, String> options, Map<String, String> env, Path configFile) throws IOException {
        this.configFile = configFile;
        Properties file = new Properties();
        if (Files.isRegularFile(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                file.load(reader);
            }
        }
        for (String key : new String[] { P_AUTH_TYPE, P_GEMINI_MODEL, P_TEMPERATURE, P_VERTEX_PROJECT_ID, P_VERTEX_REGION,
                                         P_SERVICE_ACCOUNT_FILE, P_GEMINI_ENDPOINT, P_GEMINI_API_KEY }) {
            String envVar = ENV_VARS.get(key);
            if (!isBlank(options.get(key))) {
                put(key, options.get(key), "command line");
            } else if (envVar != null && !isBlank(env.get(envVar))) {
                put(key, env.get(envVar), "environment variable " + envVar);
            } else if (!isBlank(file.getProperty(key))) {
                put(key, file.getProperty(key).trim(), configFile.toString());
            }
        }
        if (!values.containsKey(P_AUTH_TYPE)) {
            // Pick the authentication that the available credentials allow
            if (values.containsKey(P_GEMINI_API_KEY)) {
                put(P_AUTH_TYPE, AUTH_TYPE_AI_STUDIO, "default (API key found)");
            } else if (values.containsKey(P_SERVICE_ACCOUNT_FILE)) {
                put(P_AUTH_TYPE, AUTH_TYPE_VERTEX_AI, "default (service account file found)");
            } else {
                put(P_AUTH_TYPE, DEFAULT_AUTH_TYPE, "default");
            }
        }
    }

    private void put(String key, String value, String source) {
        values.put(key, value.trim());
        sources.put(key, source);
    }

    @Override
    public String get(String key) {
        return values.get(key);
    }

    @Override
    public String getSecret(String key) {
        return values.get(key);
    }

    @Override
    public String configurationHint() {
        return "the environment (GEMINI_API_KEY, GOOGLE_APPLICATION_CREDENTIALS, GOOGLE_CLOUD_PROJECT), " + configFile +
            " or the command-line options (run 'osgi-ai-migrator config' to see the settings in effect)";
    }

    /** The settings in effect and where each comes from, with the API key masked. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Config file: ").append(configFile).append(Files.isRegularFile(configFile) ? "" : " (not found)").append("\n");
        for (Map.Entry<String, String> e : values.entrySet()) {
            String value = e.getKey().equals(P_GEMINI_API_KEY) ? mask(e.getValue()) : e.getValue();
            sb.append(String.format("  %-26s %-40s (%s)%n", e.getKey(), value, sources.get(e.getKey())));
        }
        return sb.toString();
    }

    private static String mask(String secret) {
        return secret.length() <= 4 ? "****" : "****" + secret.substring(secret.length() - 4);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
