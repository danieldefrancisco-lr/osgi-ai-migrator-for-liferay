package io.github.danieldefrancisco_lr.migrator.core;

import io.github.danieldefrancisco_lr.migrator.core.agent.LlmClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stands in for Gemini: records each agent's system instruction and prompt and answers with the canned
 * response in testdata/responses/<variant>/<role>.txt, falling back to testdata/responses/default/.
 * Copy of the helper in bundles/io.github.danieldefrancisco_lr.migrator.tests (the OSGi tests cannot use core's
 * test classes); keep both in sync.
 */
public class RecordingGemini implements LlmClient {

    public final Map<String, String[]> calls = new TreeMap<>();
    private final String variant;

    public RecordingGemini(String variant) {
        this.variant = variant;
    }

    @Override
    public String generateContent(String systemInstruction, String userPrompt) throws Exception {
        String role = roleOf(systemInstruction);
        synchronized (calls) {
            calls.put(role, new String[] { systemInstruction, userPrompt });
        }
        Path response = Golden.TESTDATA.resolve("responses/" + variant + "/" + role + ".txt");
        if (!Files.exists(response)) {
            response = Golden.TESTDATA.resolve("responses/default/" + role + ".txt");
        }
        return Files.readString(response, StandardCharsets.UTF_8);
    }

    static String roleOf(String systemInstruction) {
        if (systemInstruction.startsWith("You are Leonardo")) return "leonardo";
        if (systemInstruction.contains("(Backend Specialist)")) return "backend";
        if (systemInstruction.contains("(Frontend Specialist)")) return "frontend";
        throw new IllegalArgumentException("Unknown agent: " + systemInstruction.substring(0, Math.min(80, systemInstruction.length())));
    }
}
