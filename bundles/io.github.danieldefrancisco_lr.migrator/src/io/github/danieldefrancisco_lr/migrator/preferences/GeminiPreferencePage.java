package io.github.danieldefrancisco_lr.migrator.preferences;

import io.github.danieldefrancisco_lr.migrator.Activator;

import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.FileFieldEditor;
import org.eclipse.jface.preference.RadioGroupFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/**
 * Preference page for configuring Gemini connection settings (gcloud ADC, Service Account, or AI Studio).
 */
public class GeminiPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public GeminiPreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Configure Google Gemini AI authentication settings for the OSGi AI Migrator agents.");
    }

    @Override
    public void createFieldEditors() {
        // 1. Gateway Type Selector
        String[][] authTypes = new String[][] {
            {"Google Cloud ADC / gcloud CLI (Recommended: Corporate SSO, Zero Keys)", PreferenceConstants.AUTH_TYPE_GCLOUD_ADC},
            {"Google Cloud Vertex AI (Service Account JSON File Key)", PreferenceConstants.AUTH_TYPE_VERTEX_AI},
            {"Google AI Studio (API Key - Individual Developer)", PreferenceConstants.AUTH_TYPE_AI_STUDIO}
        };

        addField(new RadioGroupFieldEditor(
            PreferenceConstants.P_AUTH_TYPE,
            "Authentication Method:",
            1,
            authTypes,
            getFieldEditorParent(),
            true
        ));

        // 2. Google Cloud / Vertex AI Settings
        addField(new StringFieldEditor(
            PreferenceConstants.P_VERTEX_PROJECT_ID,
            "GCP Project ID (empty = gcloud active project):",
            getFieldEditorParent()
        ));

        addField(new StringFieldEditor(
            PreferenceConstants.P_VERTEX_REGION,
            "Vertex AI Region (e.g. us-central1, europe-west1):",
            getFieldEditorParent()
        ));

        FileFieldEditor saEditor = new FileFieldEditor(
            PreferenceConstants.P_SERVICE_ACCOUNT_FILE,
            "Service Account JSON (Only if using Service Account Key):",
            getFieldEditorParent()
        );
        saEditor.setFileExtensions(new String[] {"*.json"});
        addField(saEditor);

        // 3. AI Studio (API Key) Settings
        // Stored in Eclipse Secure Storage, not in the plain-text preference store
        addField(new SecureStringFieldEditor(
            PreferenceConstants.P_GEMINI_API_KEY,
            "Gemini API Key (Only if using AI Studio):",
            getFieldEditorParent()
        ));

        // 4. Model Selection
        String[][] models = new String[][] {
            {"Gemini 2.5 Flash (Recommended: Ultra-fast, Verified Active on Vertex AI)", "gemini-2.5-flash"},
            {"Gemini 2.5 Pro (Advanced Reasoning & Deep Architecture)", "gemini-2.5-pro"},
            {"Gemini 3.8 Flash (AI Studio Flagship)", "gemini-3.8-flash"},
            {"Gemini 3.1 Pro Preview (AI Studio Preview)", "gemini-3.1-pro-preview"}
        };

        addField(new ComboFieldEditor(
            PreferenceConstants.P_GEMINI_MODEL,
            "Gemini Model:",
            models,
            getFieldEditorParent()
        ));

        addField(new StringFieldEditor(
            PreferenceConstants.P_TEMPERATURE,
            "Model Temperature (0.0 - 1.0):",
            getFieldEditorParent()
        ));
    }

    @Override
    public void init(IWorkbench workbench) {
        getPreferenceStore().setDefault(PreferenceConstants.P_AUTH_TYPE, PreferenceConstants.AUTH_TYPE_GCLOUD_ADC);
        getPreferenceStore().setDefault(PreferenceConstants.P_VERTEX_REGION, PreferenceConstants.DEFAULT_VERTEX_REGION);
        getPreferenceStore().setDefault(PreferenceConstants.P_GEMINI_MODEL, PreferenceConstants.DEFAULT_MODEL);
        getPreferenceStore().setDefault(PreferenceConstants.P_TEMPERATURE, PreferenceConstants.DEFAULT_TEMPERATURE);
    }
}
