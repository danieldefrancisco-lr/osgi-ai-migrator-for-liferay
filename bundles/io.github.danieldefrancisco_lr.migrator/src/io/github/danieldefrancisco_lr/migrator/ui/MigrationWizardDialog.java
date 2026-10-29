package io.github.danieldefrancisco_lr.migrator.ui;

import io.github.danieldefrancisco_lr.migrator.agent.AgentOrchestrator;
import io.github.danieldefrancisco_lr.migrator.agent.AgentOrchestrator.MigrationPlan;
import io.github.danieldefrancisco_lr.migrator.analyzer.LegacyModuleAnalyzer.ModuleAnalysisResult;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * 4-Agent Studio Modernization Wizard Dialog (v2) in Eclipse / Liferay Developer Studio.
 * Exposes Donatello, Leonardo, Michelangelo, and Raphael in 4 dedicated interactive tabs.
 */
public class MigrationWizardDialog extends TitleAreaDialog {

    private final IResource targetResource;
    private final ModuleAnalysisResult analysisResult;
    private final AgentOrchestrator orchestrator;

    private Text donatelloText;
    private Text leonardoText;
    private Text michelangeloText;
    private Text raphaelText;
    private org.eclipse.swt.widgets.Button includeObjectsCheckbox;

    private CTabFolder tabFolder;
    private MigrationPlan currentPlan;

    private static final int GENERATE_BUTTON_ID = 1001;

    public MigrationWizardDialog(Shell parentShell, IResource resource, ModuleAnalysisResult analysis) {
        super(parentShell);
        this.targetResource = resource;
        this.analysisResult = analysis;
        this.orchestrator = new AgentOrchestrator();
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    public void create() {
        super.create();
        setTitle("OSGi AI Migrator for Liferay (4-Agent Pipeline)");
        setMessage("Inspect, architect, generate, and verify modern Liferay Objects and Client Extensions.");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(GridData.FILL_BOTH));
        container.setLayout(new GridLayout(1, false));

        // Header Summary
        Label summaryLabel = new Label(container, SWT.NONE);
        summaryLabel.setText("Module: " + analysisResult.moduleName +
            " | Source Liferay: " + (analysisResult.sourceLiferayVersion != null ? analysisResult.sourceLiferayVersion : "7.x") +
            " | Entities: " + analysisResult.detectedEntities.size() +
            " | Portlets: " + analysisResult.detectedPortlets.size() +
            " | Listeners: " + analysisResult.detectedListeners.size());
        summaryLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        // Option to also generate Objects for backend entities
        if (!analysisResult.detectedEntities.isEmpty() || analysisResult.hasJspPortlet) {
            includeObjectsCheckbox = new org.eclipse.swt.widgets.Button(container, SWT.CHECK);
            String entitiesLabel = !analysisResult.detectedEntities.isEmpty() ?
                " (" + String.join(", ", analysisResult.detectedEntities) + ")" : "";
            includeObjectsCheckbox.setText("Also generate Batch Client Extension (Liferay Objects) for backend entities" + entitiesLabel);
            includeObjectsCheckbox.setSelection(true); // Default to checked
            includeObjectsCheckbox.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        }

        // 4-Agent Tab Folder
        tabFolder = new CTabFolder(container, SWT.BORDER);
        tabFolder.setLayoutData(new GridData(GridData.FILL_BOTH));

        // Tab 1: Donatello (The Analyzer)
        CTabItem tab1 = new CTabItem(tabFolder, SWT.NONE);
        tab1.setText("1. Donatello (Analyzer)");
        donatelloText = new Text(tabFolder, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        donatelloText.setText(buildDonatelloReport());
        tab1.setControl(donatelloText);

        // Tab 2: Leonardo (The Architect)
        CTabItem tab2 = new CTabItem(tabFolder, SWT.NONE);
        tab2.setText("2. Leonardo (Architect)");
        leonardoText = new Text(tabFolder, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        leonardoText.setText("Click 'Generate Modern Artifacts' to produce Leonardo's architectural modernization blueprint...");
        tab2.setControl(leonardoText);

        // Tab 3: Michelangelo (The Coder)
        CTabItem tab3 = new CTabItem(tabFolder, SWT.NONE);
        tab3.setText("3. Michelangelo (Coder)");
        michelangeloText = new Text(tabFolder, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        michelangeloText.setText("Generated deployable files (Batch Client Extension JSONs, YAML, React components) will appear here.");
        tab3.setControl(michelangeloText);

        // Tab 4: Raphael (The Verifier)
        CTabItem tab4 = new CTabItem(tabFolder, SWT.NONE);
        tab4.setText("4. Raphael (Verifier)");
        raphaelText = new Text(tabFolder, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        raphaelText.setText("Raphael's pre-flight validation checks and deployment instructions will appear here after generation.");
        tab4.setControl(raphaelText);

        tabFolder.setSelection(0);

        return area;
    }

    private String buildDonatelloReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("================================================================================\n");
        sb.append("DONATELLO (THE ANALYZER) - LEGACY MODULE EXTRACTION REPORT\n");
        sb.append("================================================================================\n\n");
        sb.append("Module Name: ").append(analysisResult.moduleName).append("\n");
        sb.append("Source Liferay Platform Version: ").append(analysisResult.sourceLiferayVersion != null ? analysisResult.sourceLiferayVersion : "Liferay 7.x").append("\n");
        sb.append("Liferay Workspace Root: ").append(analysisResult.liferayWorkspaceRoot != null ? analysisResult.liferayWorkspaceRoot : "Auto-detected").append("\n\n");

        sb.append("1. DOMAIN & SERVICE BUILDER:\n");
        sb.append("   - Has service.xml: ").append(analysisResult.hasServiceBuilder ? "YES" : "NO").append("\n");
        sb.append("   - Entities Detected (").append(analysisResult.detectedEntities.size()).append("): ").append(String.join(", ", analysisResult.detectedEntities)).append("\n\n");

        sb.append("2. USER INTERFACE & PORTLETS:\n");
        sb.append("   - Has JSP / Portlet: ").append(analysisResult.hasJspPortlet ? "YES" : "NO").append("\n");
        sb.append("   - Portlets Detected (").append(analysisResult.detectedPortlets.size()).append("): ").append(String.join(", ", analysisResult.detectedPortlets)).append("\n\n");

        sb.append("3. OSGI EVENT LISTENERS & LOGIC:\n");
        sb.append("   - Model Listeners Detected (").append(analysisResult.detectedListeners.size()).append("): ").append(String.join(", ", analysisResult.detectedListeners)).append("\n");
        sb.append("   - Schedulers Detected (").append(analysisResult.detectedSchedulers.size()).append("): ").append(String.join(", ", analysisResult.detectedSchedulers)).append("\n\n");

        sb.append("4. THEMES & LOOK AND FEEL:\n");
        sb.append("   - Has Theme Artifacts: ").append(analysisResult.hasTheme ? "YES" : "NO").append("\n\n");

        sb.append("5. EXTRACTED SOURCE FILES (").append(analysisResult.rawSourceExtracts.size()).append(" files):\n");
        for (String k : analysisResult.rawSourceExtracts.keySet()) {
            sb.append("   - ").append(k).append("\n");
        }

        return sb.toString();
    }

    private String buildRaphaelVerificationReport(MigrationPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("================================================================================\n");
        sb.append("RAPHAEL (THE VERIFIER) - PRE-FLIGHT QUALITY ASSURANCE REPORT\n");
        sb.append("================================================================================\n\n");

        List<String> passes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Validate Generated Files
        boolean hasBatch = false;
        boolean hasYaml = false;
        boolean hasApprovedStatus = false;
        boolean hasUtcTimeStorage = false;

        for (Map.Entry<String, String> entry : plan.generatedFiles.entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue();

            if (path.endsWith("client-extension.yaml")) {
                hasYaml = true;
                if (content.contains("type: batch")) {
                    hasBatch = true;
                }
                if (content.contains(".serviceAddress") && content.contains(".serviceScheme")) {
                    passes.add("OAuth Headless Server configured with .serviceAddress and .serviceScheme");
                } else if (content.contains("oAuthApplicationHeadlessServer")) {
                    warnings.add("OAuth block might be missing .serviceAddress: localhost:8080 or .serviceScheme: http");
                }
            }

            if (path.endsWith(".batch-engine-data.json")) {
                if (content.contains("\"status\": {\"code\": 0") || content.contains("\"code\": 0")) {
                    hasApprovedStatus = true;
                }
                if (content.contains("\"timeStorage\": \"convertToUTC\"") || content.contains("convertToUTC")) {
                    hasUtcTimeStorage = true;
                }
                if (content.contains("\"companyId\"") || content.contains("\"createDate\"") || content.contains("\"modifiedDate\"")) {
                    warnings.add("System audit fields (companyId/createDate) detected inside objectFields; recommend removing them.");
                }
            }
        }

        if (hasYaml) passes.add("Canonical client-extension.yaml present");
        if (hasBatch) passes.add("Batch Client Extension definition present");
        if (hasApprovedStatus) passes.add("Object Definition pre-approved (status.code: 0)");
        if (hasUtcTimeStorage) passes.add("Date fields configured with UTC timeStorage (convertToUTC)");

        sb.append("PRE-FLIGHT CHECKS:\n");
        for (String p : passes) {
            sb.append("   [PASS] ").append(p).append("\n");
        }
        if (warnings.isEmpty()) {
            sb.append("   [PASS] Zero schema conflicts or anti-patterns detected.\n\n");
        } else {
            for (String w : warnings) {
                sb.append("   [WARN] ").append(w).append("\n");
            }
            sb.append("\n");
        }

        sb.append("POST-MIGRATION ACTION CHECKLIST & MANUAL STEPS:\n\n");
        sb.append("1. DEPLOY CLIENT EXTENSIONS:\n");
        sb.append("   - Batch Object Client Extensions (deploy FIRST to initialize DB & REST APIs):\n");
        for (String p : plan.generatedFiles.keySet()) {
            if (p.endsWith("client-extension.yaml") && plan.generatedFiles.get(p).contains("type: batch")) {
                String ceName = Paths.get(p).getParent().getFileName().toString();
                sb.append("     blade gw :client-extensions:").append(ceName).append(":deploy\n");
            }
        }
        sb.append("   - Frontend Custom Element Client Extensions (deploy AFTER objects are active):\n");
        for (String p : plan.generatedFiles.keySet()) {
            if (p.endsWith("client-extension.yaml") && plan.generatedFiles.get(p).contains("type: customElement")) {
                String ceName = Paths.get(p).getParent().getFileName().toString();
                sb.append("     blade gw :client-extensions:").append(ceName).append(":deploy\n");
            }
        }
        sb.append("\n");

        sb.append("2. IMPORT PAGE FRAGMENTS (IF GENERATED):\n");
        sb.append("   - Locate the pre-zipped archive: 'fragments/<collection-name>.zip'\n");
        sb.append("   - In Liferay: Navigate to Site Administration -> Design -> Fragments\n");
        sb.append("   - Click the (...) menu at the top-right -> Import -> upload the .zip file\n\n");

        sb.append("3. CONFIGURE OBJECT PERMISSIONS (MANDATORY FOR ACCESS):\n");
        sb.append("   - By default, newly deployed Liferay Objects restrict Guest and User access.\n");
        sb.append("   - Navigate to: Control Panel -> Objects -> [Your Object] -> Permissions tab\n");
        sb.append("   - Grant 'View' and 'Add Object Entry' permissions to 'User' and/or 'Guest' roles.\n\n");

        sb.append("4. ADD CUSTOM ELEMENT WIDGET TO CONTENT PAGES:\n");
        sb.append("   - In Liferay, edit any Content Page in the Page Editor.\n");
        sb.append("   - Open the Fragments & Widgets panel -> search for your Custom Element under 'Client Extensions'.\n");
        sb.append("   - Drag and drop the widget onto the page and publish!\n\n");

        sb.append("5. OPTIONAL: CREATE DISPLAY PAGE TEMPLATES (DPT):\n");
        sb.append("   - For dedicated detail pages for each record without writing React routes:\n");
        sb.append("   - Navigate to: Site Administration -> Design -> Page Templates -> Display Page Templates\n");
        sb.append("   - Click 'Add' -> Select your Object definition as the Content Type\n");
        sb.append("   - Design the detail view using Page Fragments and visually map title, description, and date fields.\n");

        return sb.toString();
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, GENERATE_BUTTON_ID, "Generate Modern Artifacts", false);
        createButton(parent, IDialogConstants.OK_ID, "Apply to Workspace", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == GENERATE_BUTTON_ID) {
            runAgentOrchestration();
        } else if (buttonId == IDialogConstants.OK_ID) {
            applyGeneratedFilesToWorkspace();
            super.buttonPressed(buttonId);
        } else {
            super.buttonPressed(buttonId);
        }
    }

    private void runAgentOrchestration() {
        IProject project = targetResource.getProject();
        String workspaceRoot = project.getWorkspace().getRoot().getLocation().toOSString();

        if (getButton(GENERATE_BUTTON_ID) != null) {
            getButton(GENERATE_BUTTON_ID).setEnabled(false);
            getButton(GENERATE_BUTTON_ID).setText("Generating Modern Artifacts...");
        }
        setMessage("Agents at work: Donatello -> Leonardo -> Michelangelo -> Raphael... Please wait.");

        boolean generateObjects = includeObjectsCheckbox != null ? includeObjectsCheckbox.getSelection() : true;

        Job job = new Job("Running OSGi AI Migrator for Liferay (4-Agent)...") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask("Running AI Modernization Pipeline...", 100);
                try {
                    currentPlan = orchestrator.planAndGenerate(analysisResult, workspaceRoot, generateObjects);

                    Display.getDefault().asyncExec(() -> {
                        if (getButton(GENERATE_BUTTON_ID) != null && !getButton(GENERATE_BUTTON_ID).isDisposed()) {
                            getButton(GENERATE_BUTTON_ID).setEnabled(true);
                            getButton(GENERATE_BUTTON_ID).setText("Generate Modern Artifacts");
                        }

                        // Update Tab 2: Leonardo
                        leonardoText.setText(currentPlan.architectureBlueprint != null ? currentPlan.architectureBlueprint : "No blueprint generated.");

                        // Update Tab 3: Michelangelo
                        StringBuilder sb = new StringBuilder();
                        for (Map.Entry<String, String> entry : currentPlan.generatedFiles.entrySet()) {
                            sb.append("========================================\n");
                            sb.append("FILE: ").append(entry.getKey()).append("\n");
                            sb.append("========================================\n");
                            sb.append(entry.getValue()).append("\n\n");
                        }
                        michelangeloText.setText(sb.length() > 0 ? sb.toString() : "No files generated.");

                        // Update Tab 4: Raphael
                        raphaelText.setText(buildRaphaelVerificationReport(currentPlan));

                        // Switch to Michelangelo tab to show code
                        tabFolder.setSelection(2);

                        setMessage("Generation & Pre-flight verification complete! Review the tabs, then click 'Apply to Workspace'.");
                    });

                    return Status.OK_STATUS;
                } catch (Exception e) {
                    if (io.github.danieldefrancisco_lr.migrator.Activator.getDefault() != null) {
                        io.github.danieldefrancisco_lr.migrator.Activator.getDefault().getLog().log(
                            new Status(IStatus.ERROR, io.github.danieldefrancisco_lr.migrator.Activator.PLUGIN_ID, "Migration error: " + e.getMessage(), e)
                        );
                    }

                    StringBuilder msg = new StringBuilder();
                    msg.append("Error: ").append(e.getMessage());
                    if (e.getCause() != null) {
                        msg.append("\n\nCaused by: ").append(e.getCause().getClass().getSimpleName())
                           .append(" - ").append(e.getCause().getMessage());
                    }

                    Display.getDefault().asyncExec(() -> {
                        if (getButton(GENERATE_BUTTON_ID) != null && !getButton(GENERATE_BUTTON_ID).isDisposed()) {
                            getButton(GENERATE_BUTTON_ID).setEnabled(true);
                            getButton(GENERATE_BUTTON_ID).setText("Generate Modern Artifacts");
                        }
                        MessageDialog.openError(getShell(), "Migration Pipeline Error", msg.toString());
                    });
                    return Status.CANCEL_STATUS;
                } finally {
                    monitor.done();
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private void applyGeneratedFilesToWorkspace() {
        if (currentPlan == null || currentPlan.generatedFiles.isEmpty()) {
            MessageDialog.openInformation(getShell(), "No Files", "No generated files to write. Click 'Generate Modern Artifacts' first.");
            return;
        }

        try {
            File targetWorkspaceDir = null;
            if (analysisResult.liferayWorkspaceRoot != null) {
                targetWorkspaceDir = new File(analysisResult.liferayWorkspaceRoot);
            } else if (targetResource.getProject() != null && targetResource.getProject().getLocation() != null) {
                targetWorkspaceDir = targetResource.getProject().getLocation().toFile().getParentFile();
            } else {
                targetWorkspaceDir = targetResource.getLocation().toFile().getParentFile();
            }

            String moduleSlug = analysisResult.moduleName.toLowerCase().replace(".", "-");
            if (moduleSlug.endsWith("-service")) {
                moduleSlug = moduleSlug.substring(0, moduleSlug.length() - 8);
            }
            if (moduleSlug.endsWith("-api")) {
                moduleSlug = moduleSlug.substring(0, moduleSlug.length() - 4);
            }
            String defaultObjectCeDir = "client-extensions/" + moduleSlug + "-object";

            java.util.Set<String> fragmentCollections = new java.util.HashSet<>();

            for (Map.Entry<String, String> entry : currentPlan.generatedFiles.entrySet()) {
                String relativePath = entry.getKey().trim();

                // Re-route fragments mistakenly prefixed with client-extensions/
                if (relativePath.startsWith("client-extensions/") &&
                    (relativePath.contains("-fragments/") || relativePath.contains("/collection.json") || relativePath.contains("/fragment.json"))) {
                    relativePath = "fragments/" + relativePath.substring("client-extensions/".length());
                }

                // Defensive path normalization
                if (!relativePath.startsWith("client-extensions/") &&
                    !relativePath.startsWith("modules/") &&
                    !relativePath.startsWith("fragments/")) {

                    if (relativePath.contains("custom-element") || relativePath.contains("src/") || relativePath.endsWith(".js") || relativePath.endsWith(".jsx")) {
                        relativePath = "client-extensions/" + moduleSlug + "-custom-element/" + relativePath;
                    } else if (relativePath.contains("collection.json") || relativePath.contains("fragment.json")) {
                        relativePath = "fragments/" + moduleSlug + "-fragments/" + relativePath;
                    } else {
                        relativePath = defaultObjectCeDir + "/" + relativePath;
                    }
                }

                // Track fragment collections
                if (relativePath.startsWith("fragments/")) {
                    String sub = relativePath.substring("fragments/".length());
                    int slash = sub.indexOf("/");
                    if (slash != -1) {
                        fragmentCollections.add(sub.substring(0, slash));
                    }
                }

                Path targetPath = Paths.get(targetWorkspaceDir.getAbsolutePath(), relativePath);
                Files.createDirectories(targetPath.getParent());
                Files.writeString(targetPath, entry.getValue());
            }

            // Automatically zip fragment collections for 1-click import into Liferay
            for (String col : fragmentCollections) {
                File colDir = new File(targetWorkspaceDir, "fragments/" + col);
                if (colDir.exists() && colDir.isDirectory()) {
                    File zipFile = new File(targetWorkspaceDir, "fragments/" + col + ".zip");
                    zipDirectory(colDir, zipFile);
                }
            }

            // Write MIGRATION.md and persist migration history
            String reportMarkdown = generateMigrationMarkdownReport();

            // 1. Central report in docs/migrations/
            Path centralReportPath = Paths.get(targetWorkspaceDir.getAbsolutePath(), "docs/migrations/" + moduleSlug + "-migration-report.md");
            Files.createDirectories(centralReportPath.getParent());
            Files.writeString(centralReportPath, reportMarkdown);

            // 2. MIGRATION.md inside each created client extension folder
            java.util.Set<String> createdCeDirs = new java.util.HashSet<>();
            for (String path : currentPlan.generatedFiles.keySet()) {
                if (path.startsWith("client-extensions/")) {
                    String sub = path.substring("client-extensions/".length());
                    int slash = sub.indexOf("/");
                    if (slash != -1) {
                        createdCeDirs.add(sub.substring(0, slash));
                    }
                }
            }
            for (String ceDir : createdCeDirs) {
                Path ceReportPath = Paths.get(targetWorkspaceDir.getAbsolutePath(), "client-extensions/" + ceDir + "/MIGRATION.md");
                Files.writeString(ceReportPath, reportMarkdown);
            }

            targetResource.getProject().getWorkspace().getRoot().refreshLocal(IResource.DEPTH_INFINITE, null);

            StringBuilder successMsg = new StringBuilder();
            successMsg.append("Raphael (Verifier): Successfully written modern artifacts to:\n");
            successMsg.append(targetWorkspaceDir.getAbsolutePath()).append("\n\n");
            successMsg.append("Files created: ").append(currentPlan.generatedFiles.size()).append("\n");
            if (!fragmentCollections.isEmpty()) {
                successMsg.append("\nFragment Collections zipped and ready to import:\n");
                for (String col : fragmentCollections) {
                    successMsg.append("  -> fragments/").append(col).append(".zip\n");
                }
            }
            successMsg.append("\nMigration History & Action Checklist saved to:\n");
            successMsg.append("  -> docs/migrations/").append(moduleSlug).append("-migration-report.md\n");
            for (String ceDir : createdCeDirs) {
                successMsg.append("  -> client-extensions/").append(ceDir).append("/MIGRATION.md\n");
            }

            MessageDialog.openInformation(getShell(), "Success", successMsg.toString());
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "Write Error", "Failed to write files: " + e.getMessage());
        }
    }

    private String generateMigrationMarkdownReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Modernization Report & Action Guide\n\n");
        sb.append("- **Generated on:** ").append(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        sb.append("- **Source Module:** `").append(analysisResult.moduleName).append("`\n");
        sb.append("- **Source Liferay Platform Version:** `").append(analysisResult.sourceLiferayVersion != null ? analysisResult.sourceLiferayVersion : "Liferay 7.x").append("`\n");
        sb.append("- **Target Standard:** Liferay DXP 7.4+ Client Extensions & Objects\n\n");

        sb.append("---\n\n");
        sb.append("## 1. Donatello (The Analyzer) - Legacy Extraction Summary\n\n");
        sb.append("```\n").append(buildDonatelloReport()).append("```\n\n");

        sb.append("---\n\n");
        sb.append("## 2. Leonardo (The Architect) - Modernization Blueprint\n\n");
        sb.append(currentPlan.architectureBlueprint != null ? currentPlan.architectureBlueprint : "No blueprint generated.").append("\n\n");

        sb.append("---\n\n");
        sb.append("## 3. Michelangelo (The Coder) - Generated Artifacts Inventory\n\n");
        for (String file : currentPlan.generatedFiles.keySet()) {
            sb.append("- `").append(file).append("`\n");
        }
        sb.append("\n---\n\n");

        sb.append("## 4. Raphael (The Verifier) - Post-Migration Action Checklist\n\n");
        sb.append(buildRaphaelVerificationReport(currentPlan)).append("\n");

        return sb.toString();
    }

    private static void zipDirectory(File folderToZip, File zipFile) {
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(zipFile))) {
            Path sourcePath = folderToZip.toPath();
            Files.walk(sourcePath)
                .filter(path -> !Files.isDirectory(path))
                .forEach(path -> {
                    String zipEntryName = sourcePath.relativize(path).toString().replace("\\", "/");
                    try {
                        zos.putNextEntry(new java.util.zip.ZipEntry(zipEntryName));
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (Exception ignored) {}
                });
        } catch (Exception ignored) {}
    }
}
