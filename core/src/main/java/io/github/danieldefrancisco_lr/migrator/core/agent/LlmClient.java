package io.github.danieldefrancisco_lr.migrator.core.agent;

/**
 * Text generation backend used by the agents.
 */
public interface LlmClient {

    String generateContent(String systemInstruction, String userPrompt) throws Exception;
}
