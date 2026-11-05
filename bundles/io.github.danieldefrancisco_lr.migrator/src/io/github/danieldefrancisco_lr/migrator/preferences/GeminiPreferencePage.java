package io.github.danieldefrancisco_lr.migrator.preferences;

import io.github.danieldefrancisco_lr.migrator.Activator;
import io.github.danieldefrancisco_lr.migrator.core.agent.GeminiSettings;

import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.FileFieldEditor;
import org.eclipse.jface.preference.RadioGroupFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/**
 * Preference page of the AI provider: Google Gemini (gcloud ADC, Service Account or AI Studio), Anthropic Claude, or
 * any OpenAI-compatible server. The class keeps its name so the page id in plugin.xml and saved settings stay valid.
 */
public class GeminiPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public GeminiPreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Choose the AI provider of the OSGi AI Migrator agents and configure its connection. " +
            "Only the section of the selected provider is used.");
    }

    @Override
    public void createFieldEditors() {
        addField(new ComboFieldEditor(GeminiSettings.P_PROVIDER, "AI Provider:", new String[][] {
            {"Google Gemini", GeminiSettings.PROVIDER_GEMINI},
            {"Anthropic Claude", GeminiSettings.PROVIDER_ANTHROPIC},
            {"OpenAI-compatible (OpenAI, Azure OpenAI, OpenRouter, Ollama, LM Studio...)", GeminiSettings.PROVIDER_OPENAI}
        }, getFieldEditorParent()));

        // Google Gemini
        addField(new SectionHeader("Google Gemini", getFieldEditorParent()));
        String[][] authTypes = new String[][] {
            {"Google Cloud ADC / gcloud CLI (Recommended: Corporate SSO, Zero Keys)", GeminiSettings.AUTH_TYPE_GCLOUD_ADC},
            {"Google Cloud Vertex AI (Service Account JSON File Key)", GeminiSettings.AUTH_TYPE_VERTEX_AI},
            {"Google AI Studio (API Key - Individual Developer)", GeminiSettings.AUTH_TYPE_AI_STUDIO}
        };
        addField(new RadioGroupFieldEditor(GeminiSettings.P_AUTH_TYPE, "Authentication Method:", 1, authTypes,
            getFieldEditorParent(), true));
        addField(new StringFieldEditor(GeminiSettings.P_VERTEX_PROJECT_ID, "GCP Project ID (empty = gcloud active project):",
            getFieldEditorParent()));
        addField(new StringFieldEditor(GeminiSettings.P_VERTEX_REGION, "Vertex AI Region (e.g. us-central1, europe-west1):",
            getFieldEditorParent()));
        FileFieldEditor saEditor = new FileFieldEditor(GeminiSettings.P_SERVICE_ACCOUNT_FILE,
            "Service Account JSON (Only if using Service Account Key):", getFieldEditorParent());
        saEditor.setFileExtensions(new String[] {"*.json"});
        addField(saEditor);
        // Stored in Eclipse Secure Storage, not in the plain-text preference store
        addField(new SecureStringFieldEditor(GeminiSettings.P_GEMINI_API_KEY, "Gemini API Key (Only if using AI Studio):",
            getFieldEditorParent()));
        String[][] models = new String[][] {
            {"Gemini 2.5 Flash (Recommended: Ultra-fast, Verified Active on Vertex AI)", "gemini-2.5-flash"},
            {"Gemini 2.5 Pro (Advanced Reasoning & Deep Architecture)", "gemini-2.5-pro"},
            {"Gemini 3.8 Flash (AI Studio Flagship)", "gemini-3.8-flash"},
            {"Gemini 3.1 Pro Preview (AI Studio Preview)", "gemini-3.1-pro-preview"}
        };
        addField(new ComboFieldEditor(GeminiSettings.P_GEMINI_MODEL, "Gemini Model:", models, getFieldEditorParent()));
        addField(new StringFieldEditor(GeminiSettings.P_TEMPERATURE, "Model Temperature (0.0 - 1.0):", getFieldEditorParent()));

        // Anthropic Claude
        addField(new SectionHeader("Anthropic Claude", getFieldEditorParent()));
        addField(new SecureStringFieldEditor(GeminiSettings.P_ANTHROPIC_API_KEY, "Anthropic API Key:", getFieldEditorParent()));
        addField(new StringFieldEditor(GeminiSettings.P_ANTHROPIC_MODEL, "Claude Model (e.g. claude-opus-5-5, claude-sonnet-5-5):",
            getFieldEditorParent()));
        addField(new ComboFieldEditor(GeminiSettings.P_ANTHROPIC_EFFORT, "Effort:", new String[][] {
            {"High (Recommended)", "high"}, {"Medium (faster, cheaper)", "medium"}, {"Low", "low"},
            {"Extra high", "xhigh"}, {"Max (slowest)", "max"}
        }, getFieldEditorParent()));
        addField(new StringFieldEditor(GeminiSettings.P_ANTHROPIC_ENDPOINT, "API Endpoint:", getFieldEditorParent()));

        // OpenAI-compatible
        addField(new SectionHeader("OpenAI-compatible", getFieldEditorParent()));
        addField(new StringFieldEditor(GeminiSettings.P_OPENAI_BASE_URL,
            "Base URL (e.g. https://api.openai.com/v1, http://localhost:11434/v1):", getFieldEditorParent()));
        addField(new StringFieldEditor(GeminiSettings.P_OPENAI_MODEL, "Model (e.g. gpt-5, qwen3-coder):", getFieldEditorParent()));
        addField(new SecureStringFieldEditor(GeminiSettings.P_OPENAI_API_KEY, "API Key (empty for local servers):",
            getFieldEditorParent()));
    }

    @Override
    public void init(IWorkbench workbench) {
        getPreferenceStore().setDefault(GeminiSettings.P_PROVIDER, GeminiSettings.DEFAULT_PROVIDER);
        getPreferenceStore().setDefault(GeminiSettings.P_AUTH_TYPE, GeminiSettings.AUTH_TYPE_GCLOUD_ADC);
        getPreferenceStore().setDefault(GeminiSettings.P_VERTEX_REGION, GeminiSettings.DEFAULT_VERTEX_REGION);
        getPreferenceStore().setDefault(GeminiSettings.P_GEMINI_MODEL, GeminiSettings.DEFAULT_MODEL);
        getPreferenceStore().setDefault(GeminiSettings.P_TEMPERATURE, GeminiSettings.DEFAULT_TEMPERATURE);
        getPreferenceStore().setDefault(GeminiSettings.P_ANTHROPIC_MODEL, GeminiSettings.DEFAULT_ANTHROPIC_MODEL);
        getPreferenceStore().setDefault(GeminiSettings.P_ANTHROPIC_EFFORT, GeminiSettings.DEFAULT_ANTHROPIC_EFFORT);
        getPreferenceStore().setDefault(GeminiSettings.P_ANTHROPIC_ENDPOINT, GeminiSettings.DEFAULT_ANTHROPIC_ENDPOINT);
        getPreferenceStore().setDefault(GeminiSettings.P_OPENAI_BASE_URL, GeminiSettings.DEFAULT_OPENAI_BASE_URL);
    }

    /** A bold title across the page that starts the settings of one provider; it stores nothing. */
    private static final class SectionHeader extends FieldEditor {

        private Label label;

        SectionHeader(String title, Composite parent) {
            super("", title, parent);
        }

        @Override
        protected void adjustForNumColumns(int numColumns) {
            ((GridData) label.getLayoutData()).horizontalSpan = numColumns;
        }

        @Override
        protected void doFillIntoGrid(Composite parent, int numColumns) {
            label = new Label(parent, SWT.NONE);
            label.setText(getLabelText());
            label.setFont(JFaceResources.getBannerFont());
            GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false, numColumns, 1);
            data.verticalIndent = 12;
            label.setLayoutData(data);
        }

        @Override
        public int getNumberOfControls() {
            return 1;
        }

        @Override
        protected void doLoad() {
        }

        @Override
        protected void doLoadDefault() {
        }

        @Override
        protected void doStore() {
        }

        @Override
        public void store() {
        }
    }
}
