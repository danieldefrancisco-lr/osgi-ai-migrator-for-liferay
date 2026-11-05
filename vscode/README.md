# OSGi AI Migrator for Liferay

AI-assisted modernization of legacy Liferay 7.x OSGi modules into Liferay Objects and Client Extensions,
with the AI provider you choose: Google Gemini, Anthropic Claude or any OpenAI-compatible server (OpenAI,
Azure OpenAI, OpenRouter, Ollama, LM Studio).

Right-click a module folder in the Explorer and choose **Migrate OSGi Module to Client Extensions...** to run a
4-agent pipeline:

1. **Donatello** analyzes `service.xml`, JSPs, portlets, model listeners, schedulers, notifications and theme files.
2. **Leonardo** designs the target architecture, including the API contract between backend and frontend.
3. **Michelangelo** generates the Batch Client Extension (Objects and picklists) and a React Custom Element.
4. **Raphael** verifies the result before you click **Apply to Workspace**, which writes the files into the
   Liferay workspace with a migration report and a checklist of manual steps.

## Requirements

- Java 17 or later (set **OSGi AI Migrator: Java Home** if `java` on your PATH is older).
- An AI provider, chosen with **OSGi AI Migrator: Provider**. API keys are set with the command
  **OSGi AI Migrator: Set AI Provider API Key** and kept in VS Code secret storage.
  - **Google Gemini** (default), one of:
    - **Google Cloud ADC** (default): `gcloud auth login`, plus **Project Id** if gcloud has no active project.
    - **Vertex AI** with a service account JSON file.
    - **Google AI Studio**: set the auth type to `AI_STUDIO` and set the Gemini API key.
  - **Anthropic Claude**: an Anthropic API key; model (default `claude-opus-5-5`) and effort in the settings.
  - **OpenAI-compatible**: a model and, for servers other than OpenAI, the base URL (Ollama:
    `http://localhost:11434/v1`); the API key is optional for local servers.

The same migration is available for Eclipse, JetBrains IDEs and the command line:
https://github.com/danieldefrancisco-lr/osgi-ai-migrator-for-liferay

*Independent personal project. Not affiliated with, endorsed by, or supported by Liferay, Inc. Liferay and
Liferay DXP are trademarks of Liferay, Inc.*
