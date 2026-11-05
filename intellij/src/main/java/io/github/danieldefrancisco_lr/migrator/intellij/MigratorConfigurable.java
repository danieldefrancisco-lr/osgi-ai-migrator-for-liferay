package io.github.danieldefrancisco_lr.migrator.intellij;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;

import io.github.danieldefrancisco_lr.migrator.core.agent.GeminiSettings;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.text.JTextComponent;

/**
 * Settings -> Tools -> OSGi AI Migrator for Liferay: the AI provider (Gemini, Claude or an OpenAI-compatible server)
 * and how to reach it. Only the section of the selected provider is used.
 */
public class MigratorConfigurable implements Configurable {

    private static final String[] PROVIDERS = {
        GeminiSettings.PROVIDER_GEMINI, GeminiSettings.PROVIDER_ANTHROPIC, GeminiSettings.PROVIDER_OPENAI };
    private static final String[] PROVIDER_LABELS = {
        "Google Gemini", "Anthropic Claude", "OpenAI-compatible (OpenAI, Azure OpenAI, OpenRouter, Ollama, LM Studio...)" };
    private static final String[] AUTH_TYPES = {
        GeminiSettings.AUTH_TYPE_GCLOUD_ADC, GeminiSettings.AUTH_TYPE_VERTEX_AI, GeminiSettings.AUTH_TYPE_AI_STUDIO };
    private static final String[] AUTH_LABELS = {
        "Google Cloud ADC (gcloud auth login)", "Vertex AI (service account JSON)", "Google AI Studio (API key)" };
    private static final String[] EFFORTS = { "high", "medium", "low", "xhigh", "max" };
    private static final String[] EFFORT_LABELS = {
        "High (recommended)", "Medium (faster, cheaper)", "Low", "Extra high", "Max (slowest)" };
    private static final String[] SECRET_KEYS = {
        GeminiSettings.P_GEMINI_API_KEY, GeminiSettings.P_ANTHROPIC_API_KEY, GeminiSettings.P_OPENAI_API_KEY };

    private final IdeGeminiSettings settings = new IdeGeminiSettings();

    private JComboBox<String> provider;
    private JComboBox<String> authType;
    private JComboBox<String> effort;
    private TextFieldWithBrowseButton serviceAccountFile;
    /** Plain text settings by key. */
    private final Map<String, JTextComponent> texts = new LinkedHashMap<>();
    /** API keys by key, kept in the password safe. */
    private final Map<String, JBPasswordField> secrets = new LinkedHashMap<>();

    @Override
    public String getDisplayName() {
        return "OSGi AI Migrator for Liferay";
    }

    @Override
    public JComponent createComponent() {
        provider = new JComboBox<>(PROVIDER_LABELS);
        authType = new JComboBox<>(AUTH_LABELS);
        effort = new JComboBox<>(EFFORT_LABELS);
        serviceAccountFile = new TextFieldWithBrowseButton();
        serviceAccountFile.addBrowseFolderListener(null,
            FileChooserDescriptorFactory.createSingleFileDescriptor("json").withTitle("Service Account JSON"));
        texts.clear();
        secrets.clear();
        for (String key : SECRET_KEYS) {
            secrets.put(key, new JBPasswordField());
        }

        JPanel panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("AI provider:", provider)
            .addComponent(new TitledSeparator("Google Gemini"))
            .addLabeledComponent("Authentication:", authType)
            .addLabeledComponent("GCP project ID (empty = gcloud active project):", text(GeminiSettings.P_VERTEX_PROJECT_ID))
            .addLabeledComponent("Vertex AI region:", text(GeminiSettings.P_VERTEX_REGION))
            .addLabeledComponent("Service account JSON (Vertex AI):", serviceAccountFile)
            .addLabeledComponent("Gemini API key (AI Studio):", secrets.get(GeminiSettings.P_GEMINI_API_KEY))
            .addLabeledComponent("AI Studio endpoint:", text(GeminiSettings.P_GEMINI_ENDPOINT))
            .addLabeledComponent("Model:", text(GeminiSettings.P_GEMINI_MODEL))
            .addLabeledComponent("Temperature:", text(GeminiSettings.P_TEMPERATURE))
            .addComponent(new TitledSeparator("Anthropic Claude"))
            .addLabeledComponent("Anthropic API key:", secrets.get(GeminiSettings.P_ANTHROPIC_API_KEY))
            .addLabeledComponent("Model (e.g. claude-opus-5-5, claude-sonnet-5-5):", text(GeminiSettings.P_ANTHROPIC_MODEL))
            .addLabeledComponent("Effort:", effort)
            .addLabeledComponent("API endpoint:", text(GeminiSettings.P_ANTHROPIC_ENDPOINT))
            .addComponent(new TitledSeparator("OpenAI-compatible"))
            .addLabeledComponent("Base URL (e.g. http://localhost:11434/v1 for Ollama):", text(GeminiSettings.P_OPENAI_BASE_URL))
            .addLabeledComponent("Model (e.g. gpt-5, qwen3-coder):", text(GeminiSettings.P_OPENAI_MODEL))
            .addLabeledComponent("API key (empty for local servers):", secrets.get(GeminiSettings.P_OPENAI_API_KEY))
            .addComponentToRightColumn(new JBLabel("API keys are kept in the IDE's password safe."))
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        reset();
        return panel;
    }

    private JBTextField text(String key) {
        JBTextField field = new JBTextField();
        texts.put(key, field);
        return field;
    }

    @Override
    public boolean isModified() {
        if (!Objects.equals(selected(PROVIDERS, provider), settings.get(GeminiSettings.P_PROVIDER))
            || !Objects.equals(selected(AUTH_TYPES, authType), settings.get(GeminiSettings.P_AUTH_TYPE))
            || !Objects.equals(selected(EFFORTS, effort), settings.get(GeminiSettings.P_ANTHROPIC_EFFORT))
            || !serviceAccountFile.getText().equals(settings.get(GeminiSettings.P_SERVICE_ACCOUNT_FILE))) {
            return true;
        }
        for (Map.Entry<String, JTextComponent> e : texts.entrySet()) {
            if (!e.getValue().getText().equals(settings.get(e.getKey()))) {
                return true;
            }
        }
        for (Map.Entry<String, JBPasswordField> e : secrets.entrySet()) {
            if (!new String(e.getValue().getPassword()).equals(Objects.toString(settings.getSecret(e.getKey()), ""))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void apply() {
        settings.set(GeminiSettings.P_PROVIDER, selected(PROVIDERS, provider));
        settings.set(GeminiSettings.P_AUTH_TYPE, selected(AUTH_TYPES, authType));
        settings.set(GeminiSettings.P_ANTHROPIC_EFFORT, selected(EFFORTS, effort));
        settings.set(GeminiSettings.P_SERVICE_ACCOUNT_FILE, serviceAccountFile.getText().trim());
        for (Map.Entry<String, JTextComponent> e : texts.entrySet()) {
            settings.set(e.getKey(), e.getValue().getText().trim());
        }
        for (Map.Entry<String, JBPasswordField> e : secrets.entrySet()) {
            String value = new String(e.getValue().getPassword()).trim();
            // Only touch the password safe when the key changed
            if (!value.equals(Objects.toString(settings.getSecret(e.getKey()), ""))) {
                settings.setSecret(e.getKey(), value);
            }
        }
    }

    @Override
    public void reset() {
        select(PROVIDERS, provider, settings.get(GeminiSettings.P_PROVIDER));
        select(AUTH_TYPES, authType, settings.get(GeminiSettings.P_AUTH_TYPE));
        select(EFFORTS, effort, settings.get(GeminiSettings.P_ANTHROPIC_EFFORT));
        serviceAccountFile.setText(settings.get(GeminiSettings.P_SERVICE_ACCOUNT_FILE));
        for (Map.Entry<String, JTextComponent> e : texts.entrySet()) {
            e.getValue().setText(settings.get(e.getKey()));
        }
        for (Map.Entry<String, JBPasswordField> e : secrets.entrySet()) {
            e.getValue().setText(Objects.toString(settings.getSecret(e.getKey()), ""));
        }
    }

    private static String selected(String[] values, JComboBox<String> combo) {
        return values[Math.max(combo.getSelectedIndex(), 0)];
    }

    private static void select(String[] values, JComboBox<String> combo, String value) {
        combo.setSelectedIndex(Math.max(Arrays.asList(values).indexOf(value), 0));
    }
}
