package io.github.danieldefrancisco_lr.migrator.core.output;

import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator.MigrationPlan;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleAnalysisResult;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text reports of the Donatello (analysis) and Raphael (verification) agents, and the MIGRATION.md guide.
 */
public class MigrationReports {

    private static final String API_CALLS = "(?:getObjectEntries|getObjectEntry|createObjectEntry|updateObjectEntry|deleteObjectEntry|normalizeUrl|fetchWithAuth)";
    /** An UPPER_SNAKE externalReferenceCode, e.g. DEVCON_REGISTRATION. */
    private static final String ERC = "[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+";
    private static final Pattern ERC_CONSTANT = Pattern.compile("(?:const|let|var)\\s+(\\w+)\\s*=\\s*['\"](" + ERC + ")['\"]");
    private static final Pattern ERC_LITERAL_CALL = Pattern.compile(API_CALLS + "\\(\\s*['\"](" + ERC + ")['\"]");
    /** Object API sub-paths that Liferay does not have, e.g. `${OBJECT_PATH}/count`. */
    private static final Pattern INVENTED_ENDPOINT = Pattern.compile("/(count|search|list|all)['\"`?]");
    private static final Pattern URL_WITHOUT_O_PREFIX = Pattern.compile("['\"`]/(?:headless-[\\w-]+|c)/");

    public static String buildDonatelloReport(ModuleAnalysisResult analysisResult) {
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
        sb.append("   - Schedulers Detected (").append(analysisResult.detectedSchedulers.size()).append("): ").append(String.join(", ", analysisResult.detectedSchedulers)).append("\n");
        sb.append("   - Email/Notifications Detected (").append(analysisResult.detectedNotifications.size()).append("): ").append(String.join(", ", analysisResult.detectedNotifications)).append("\n\n");

        sb.append("4. THEMES & LOOK AND FEEL:\n");
        sb.append("   - Has Theme Artifacts: ").append(analysisResult.hasTheme ? "YES" : "NO").append("\n\n");

        sb.append("5. EXTRACTED SOURCE FILES (").append(analysisResult.rawSourceExtracts.size()).append(" files):\n");
        for (String k : analysisResult.rawSourceExtracts.keySet()) {
            sb.append("   - ").append(k).append("\n");
        }

        return sb.toString();
    }

    public static String buildRaphaelVerificationReport(ModuleAnalysisResult analysisResult, MigrationPlan plan) {
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
                if (content.startsWith("apiVersion:") || content.contains("\napiVersion:") ||
                    content.startsWith("spec:") || content.contains("\nspec:")) {
                    warnings.add(path + " uses a Kubernetes-style format (apiVersion/kind/metadata/spec). Liferay expects top-level 'assemble' and client extension id keys; 'blade gw build' fails with 'TextNode cannot be cast to ObjectNode'.");
                }
            }

            if (path.endsWith(".batch-engine-data.json")) {
                if (content.contains("\"status\": {\"code\": 0") || content.contains("\"code\": 0")) {
                    hasApprovedStatus = true;
                }
                if (content.contains("\"companyId\"") || content.contains("\"createDate\"") || content.contains("\"modifiedDate\"")) {
                    warnings.add("System audit fields (companyId/createDate) detected inside objectFields; recommend removing them.");
                }
                if (content.contains("\"objectValidations\"") || content.contains("\"objectValidationRules\"")) {
                    warnings.add("Invalid 'objectValidations' or 'objectValidationRules' detected inside ObjectDefinition DTO. This causes NoSuchFieldException!");
                }
                if (content.contains("\"localized\":")) {
                    warnings.add("Property 'localized' detected inside ObjectField item. Liferay ObjectField does not accept 'localized'; use root 'enableLocalization' instead.");
                }
            }

            if (path.endsWith(".js") || path.endsWith(".jsx")) {
                checkApiPaths(path, content, warnings);
            }
        }

        if (hasYaml) passes.add("Canonical client-extension.yaml present");
        if (hasBatch) passes.add("Batch Client Extension definition present (Liferay 7.4+ schema)");
        if (hasApprovedStatus) passes.add("Object Definition pre-approved (status.code: 0)");

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

        if (!analysisResult.detectedNotifications.isEmpty()) {
            sb.append("\n6. NOTIFICATION TEMPLATE & OBJECT ACTION SETUP (REPLACING LEGACY MAILSERVICE):\n");
            sb.append("   - Legacy email sending detected in: ").append(String.join(", ", analysisResult.detectedNotifications)).append("\n");
            sb.append("   - Modern Liferay replaces hardcoded Java MailService with Native Notification Templates & Object Actions:\n");
            sb.append("     a) In Liferay: Control Panel -> Notifications -> Notification Templates\n");
            sb.append("     b) Click (+) Add -> select 'Email'\n");
            sb.append("        - Name: '").append(analysisResult.moduleName).append(" Confirmation Email'\n");
            sb.append("        - Subject: Your Registration is Confirmed!\n");
            sb.append("        - Body: Design using object tokens e.g. [%FIRSTNAME%], [%EMAILADDRESS%]\n");
            sb.append("     c) In Liferay: Control Panel -> Objects -> [Your Object] -> Actions tab\n");
            sb.append("     d) Click (+) Add Action:\n");
            sb.append("        - Action Label: 'Send Confirmation Email'\n");
            sb.append("        - Trigger: 'On After Add' (fires automatically when Custom Element creates entry)\n");
            sb.append("        - Action: 'Notification'\n");
            sb.append("        - Template: Select your created template\n");
            sb.append("        - Recipient: Select the recipient field (e.g. 'emailAddress')\n");
        }

        return sb.toString();
    }

    /** Flags frontend code that calls Liferay APIs with an externalReferenceCode or a URL missing '/o/'. */
    private static void checkApiPaths(String path, String content, List<String> warnings) {
        Matcher constants = ERC_CONSTANT.matcher(content);
        while (constants.find()) {
            String name = constants.group(1);
            if (Pattern.compile(API_CALLS + "\\(\\s*" + Pattern.quote(name) + "\\b").matcher(content).find()) {
                warnings.add(path + " passes externalReferenceCode '" + constants.group(2) + "' (" + name +
                    ") as an API path; use the REST endpoint from the blueprint's API CONTRACT, e.g. '/o/c/<lowercase plural name>'.");
            }
        }
        Matcher literals = ERC_LITERAL_CALL.matcher(content);
        while (literals.find()) {
            warnings.add(path + " passes externalReferenceCode '" + literals.group(1) +
                "' as an API path; use the REST endpoint from the blueprint's API CONTRACT, e.g. '/o/c/<lowercase plural name>'.");
        }
        Matcher invented = INVENTED_ENDPOINT.matcher(content);
        if (invented.find()) {
            warnings.add(path + " calls a '/" + invented.group(1) + "' endpoint, which Liferay Objects do not have; " +
                "list with GET [endpoint]?page=1&pageSize=N and read 'totalCount' for counts (countObjectEntries).");
        }
        if (URL_WITHOUT_O_PREFIX.matcher(content).find()) {
            warnings.add(path + " calls a Liferay API URL without the '/o/' prefix (e.g. '/headless-admin-list-type/...' instead of '/o/headless-admin-list-type/...').");
        }
    }

    public static String generateMigrationMarkdownReport(ModuleAnalysisResult analysisResult, MigrationPlan currentPlan) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Modernization Report & Action Guide\n\n");
        sb.append("- **Generated on:** ").append(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        sb.append("- **Source Module:** `").append(analysisResult.moduleName).append("`\n");
        sb.append("- **Source Liferay Platform Version:** `").append(analysisResult.sourceLiferayVersion != null ? analysisResult.sourceLiferayVersion : "Liferay 7.x").append("`\n");
        sb.append("- **Target Standard:** Liferay DXP 7.4+ Client Extensions & Objects\n\n");

        sb.append("---\n\n");
        sb.append("## 1. Donatello (The Analyzer) - Legacy Extraction Summary\n\n");
        sb.append("```\n").append(buildDonatelloReport(analysisResult)).append("```\n\n");

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
        sb.append(buildRaphaelVerificationReport(analysisResult, currentPlan)).append("\n");

        return sb.toString();
    }
}
