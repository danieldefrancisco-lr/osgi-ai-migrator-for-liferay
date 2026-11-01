package io.github.danieldefrancisco_lr.migrator.intellij;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;

import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator;
import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator.MigrationPlan;
import io.github.danieldefrancisco_lr.migrator.core.agent.GeminiClient;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleAnalysisResult;
import io.github.danieldefrancisco_lr.migrator.core.output.ArtifactWriter;
import io.github.danieldefrancisco_lr.migrator.core.output.MigrationReports;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;

import org.jetbrains.annotations.NotNull;

/**
 * The 4-agent wizard: Donatello (analysis), Leonardo (blueprint), Michelangelo (generated files) and
 * Raphael (verification), with the same flow as the Eclipse plugin: generate, review, apply to the workspace.
 */
public class MigrationDialog extends DialogWrapper {

    private static final Logger LOG = Logger.getInstance(MigrationDialog.class);

    private final Project project;
    private final ModuleAnalysisResult analysis;

    private final JBTabbedPane tabs = new JBTabbedPane();
    private final JBTextArea donatelloText = textArea();
    private final JBTextArea leonardoText = textArea();
    private final JBTextArea michelangeloText = textArea();
    private final JBTextArea raphaelText = textArea();
    private final JBCheckBox includeObjects = new JBCheckBox("Also generate Liferay Objects (Batch Client Extension) for the entities used", true);

    private final Action generateAction = new AbstractAction("Generate Modern Artifacts") {
        @Override
        public void actionPerformed(ActionEvent e) {
            generate();
        }
    };
    private final Action applyAction = new AbstractAction("Apply to Workspace") {
        @Override
        public void actionPerformed(ActionEvent e) {
            applyToWorkspace();
        }
    };

    private MigrationPlan plan;

    public MigrationDialog(Project project, ModuleAnalysisResult analysis) {
        super(project, true);
        this.project = project;
        this.analysis = analysis;
        setTitle("OSGi AI Migrator for Liferay (4-Agent Pipeline): " + analysis.moduleName);
        setOKButtonText("Close");
        applyAction.setEnabled(false);
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        donatelloText.setText(MigrationReports.buildDonatelloReport(analysis));
        leonardoText.setText("Click 'Generate Modern Artifacts' to let Leonardo design the target architecture.");
        michelangeloText.setText("Generated files will appear here.");
        raphaelText.setText("Pre-flight verification will appear here after generation.");

        tabs.addTab("1. Donatello (Analyzer)", new JBScrollPane(donatelloText));
        tabs.addTab("2. Leonardo (Architect)", new JBScrollPane(leonardoText));
        tabs.addTab("3. Michelangelo (Coder)", new JBScrollPane(michelangeloText));
        tabs.addTab("4. Raphael (Verifier)", new JBScrollPane(raphaelText));

        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        panel.add(includeObjects, BorderLayout.NORTH);
        panel.add(tabs, BorderLayout.CENTER);
        panel.setPreferredSize(JBUI.size(1000, 700));
        return panel;
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[] { generateAction, applyAction, getOKAction() };
    }

    private void generate() {
        boolean generateObjects = includeObjects.isSelected();
        generateAction.setEnabled(false);
        ProgressManager.getInstance().run(new Task.Modal(project, "Running OSGi AI Migrator for Liferay (4-Agent)...", false) {
            private MigrationPlan result;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Leonardo and Michelangelo are working with Gemini, this can take a minute...");
                try {
                    AgentOrchestrator orchestrator = new AgentOrchestrator(new GeminiClient(new IdeGeminiSettings()));
                    result = orchestrator.planAndGenerate(analysis, project.getBasePath(), generateObjects);
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }

            @Override
            public void onSuccess() {
                plan = result;
                leonardoText.setText(plan.architectureBlueprint != null ? plan.architectureBlueprint : "No blueprint generated.");
                michelangeloText.setText(filesText(plan));
                raphaelText.setText(MigrationReports.buildRaphaelVerificationReport(analysis, plan));
                leonardoText.setCaretPosition(0);
                michelangeloText.setCaretPosition(0);
                raphaelText.setCaretPosition(0);
                tabs.setSelectedIndex(2);
                applyAction.setEnabled(!plan.generatedFiles.isEmpty());
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                LOG.warn("Migration error", error);
                StringBuilder msg = new StringBuilder("Error: ").append(error.getMessage());
                Throwable cause = error.getCause() != null ? error.getCause().getCause() : null;
                if (cause != null) {
                    msg.append("\n\nCaused by: ").append(cause.getClass().getSimpleName()).append(" - ").append(cause.getMessage());
                }
                Messages.showErrorDialog(project, msg.toString(), "Migration Pipeline Error");
            }

            @Override
            public void onFinished() {
                generateAction.setEnabled(true);
            }
        });
    }

    private void applyToWorkspace() {
        if (plan == null || plan.generatedFiles.isEmpty()) {
            return;
        }
        File workspace = new File(analysis.liferayWorkspaceRoot);
        try {
            ArtifactWriter.WriteResult written = ArtifactWriter.writeArtifacts(analysis, plan, workspace);
            VirtualFile root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(workspace);
            if (root != null) {
                root.refresh(true, true);
            }

            StringBuilder msg = new StringBuilder("Raphael (Verifier): Successfully written modern artifacts to:\n")
                .append(workspace.getAbsolutePath()).append("\n\n")
                .append("Files created: ").append(plan.generatedFiles.size()).append("\n");
            if (!written.fragmentCollections.isEmpty()) {
                msg.append("\nFragment Collections zipped and ready to import:\n");
                for (String col : written.fragmentCollections) {
                    msg.append("  -> fragments/").append(col).append(".zip\n");
                }
            }
            msg.append("\nMigration History & Action Checklist saved to:\n")
                .append("  -> docs/migrations/").append(written.moduleSlug).append("-migration-report.md\n");
            for (String dir : written.createdCeDirs) {
                msg.append("  -> client-extensions/").append(dir).append("/MIGRATION.md\n");
            }
            Messages.showInfoMessage(project, msg.toString(), "Success");
        } catch (Exception e) {
            Messages.showErrorDialog(project, "Failed to write files: " + e.getMessage(), "Write Error");
        }
    }

    private static String filesText(MigrationPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : plan.generatedFiles.entrySet()) {
            sb.append("========================================\n");
            sb.append("FILE: ").append(entry.getKey()).append("\n");
            sb.append("========================================\n");
            sb.append(entry.getValue()).append("\n\n");
        }
        return sb.length() > 0 ? sb.toString() : "No files generated.";
    }

    private static JBTextArea textArea() {
        JBTextArea area = new JBTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scaleFontSize(12f)));
        return area;
    }
}
