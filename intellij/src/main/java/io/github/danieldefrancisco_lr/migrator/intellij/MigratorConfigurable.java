package io.github.danieldefrancisco_lr.migrator.intellij;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;

import io.github.danieldefrancisco_lr.migrator.core.agent.GeminiSettings;

import java.util.Objects;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Settings -> Tools -> OSGi AI Migrator for Liferay: how to reach Gemini.
 */
public class MigratorConfigurable implements Configurable {

    private static final String[] AUTH_TYPES = {
        GeminiSettings.AUTH_TYPE_GCLOUD_ADC, GeminiSettings.AUTH_TYPE_VERTEX_AI, GeminiSettings.AUTH_TYPE_AI_STUDIO };
    private static final String[] AUTH_LABELS = {
        "Google Cloud ADC (gcloud auth login)", "Vertex AI (service account JSON)", "Google AI Studio (API key)" };

    private final IdeGeminiSettings settings = new IdeGeminiSettings();

    private JComboBox<String> authType;
    private JBTextField projectId;
    private JBTextField region;
    private TextFieldWithBrowseButton serviceAccountFile;
    private JBPasswordField apiKey;
    private JBTextField endpoint;
    private JBTextField model;
    private JBTextField temperature;

    @Override
    public String getDisplayName() {
        return "OSGi AI Migrator for Liferay";
    }

    @Override
    public JComponent createComponent() {
        authType = new JComboBox<>(AUTH_LABELS);
        projectId = new JBTextField();
        region = new JBTextField();
        serviceAccountFile = new TextFieldWithBrowseButton();
        serviceAccountFile.addBrowseFolderListener(null,
            FileChooserDescriptorFactory.createSingleFileDescriptor("json").withTitle("Service Account JSON"));
        apiKey = new JBPasswordField();
        endpoint = new JBTextField();
        model = new JBTextField();
        temperature = new JBTextField();

        JPanel panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Authentication:", authType)
            .addSeparator()
            .addLabeledComponent("GCP project ID (empty = gcloud active project):", projectId)
            .addLabeledComponent("Vertex AI region:", region)
            .addLabeledComponent("Service account JSON (Vertex AI):", serviceAccountFile)
            .addSeparator()
            .addLabeledComponent("Gemini API key (AI Studio):", apiKey)
            .addLabeledComponent("AI Studio endpoint:", endpoint)
            .addSeparator()
            .addLabeledComponent("Model:", model)
            .addLabeledComponent("Temperature:", temperature)
            .addComponentToRightColumn(new JBLabel("The API key is kept in the IDE's password safe."))
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        return !Objects.equals(selectedAuthType(), settings.get(GeminiSettings.P_AUTH_TYPE))
            || !projectId.getText().equals(settings.get(GeminiSettings.P_VERTEX_PROJECT_ID))
            || !region.getText().equals(settings.get(GeminiSettings.P_VERTEX_REGION))
            || !serviceAccountFile.getText().equals(settings.get(GeminiSettings.P_SERVICE_ACCOUNT_FILE))
            || !endpoint.getText().equals(settings.get(GeminiSettings.P_GEMINI_ENDPOINT))
            || !model.getText().equals(settings.get(GeminiSettings.P_GEMINI_MODEL))
            || !temperature.getText().equals(settings.get(GeminiSettings.P_TEMPERATURE))
            || !new String(apiKey.getPassword()).equals(Objects.toString(settings.getSecret(GeminiSettings.P_GEMINI_API_KEY), ""));
    }

    @Override
    public void apply() {
        settings.set(GeminiSettings.P_AUTH_TYPE, selectedAuthType());
        settings.set(GeminiSettings.P_VERTEX_PROJECT_ID, projectId.getText().trim());
        settings.set(GeminiSettings.P_VERTEX_REGION, region.getText().trim());
        settings.set(GeminiSettings.P_SERVICE_ACCOUNT_FILE, serviceAccountFile.getText().trim());
        settings.set(GeminiSettings.P_GEMINI_ENDPOINT, endpoint.getText().trim());
        settings.set(GeminiSettings.P_GEMINI_MODEL, model.getText().trim());
        settings.set(GeminiSettings.P_TEMPERATURE, temperature.getText().trim());
        settings.setSecret(GeminiSettings.P_GEMINI_API_KEY, new String(apiKey.getPassword()).trim());
    }

    @Override
    public void reset() {
        int index = java.util.Arrays.asList(AUTH_TYPES).indexOf(settings.get(GeminiSettings.P_AUTH_TYPE));
        authType.setSelectedIndex(Math.max(index, 0));
        projectId.setText(settings.get(GeminiSettings.P_VERTEX_PROJECT_ID));
        region.setText(settings.get(GeminiSettings.P_VERTEX_REGION));
        serviceAccountFile.setText(settings.get(GeminiSettings.P_SERVICE_ACCOUNT_FILE));
        endpoint.setText(settings.get(GeminiSettings.P_GEMINI_ENDPOINT));
        model.setText(settings.get(GeminiSettings.P_GEMINI_MODEL));
        temperature.setText(settings.get(GeminiSettings.P_TEMPERATURE));
        apiKey.setText(Objects.toString(settings.getSecret(GeminiSettings.P_GEMINI_API_KEY), ""));
    }

    private String selectedAuthType() {
        return AUTH_TYPES[Math.max(authType.getSelectedIndex(), 0)];
    }
}
