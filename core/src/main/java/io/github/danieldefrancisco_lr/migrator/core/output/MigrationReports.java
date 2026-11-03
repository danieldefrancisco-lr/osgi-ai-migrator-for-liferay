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
    /** The business types of Liferay Objects fields. */
    private static final List<String> BUSINESS_TYPES = List.of(
        "Aggregation", "Attachment", "AutoIncrement", "Boolean", "Date", "DateTime", "Decimal", "Encrypted", "Formula",
        "Integer", "LongInteger", "LongText", "MultiselectPicklist", "Picklist", "PrecisionDecimal", "Relationship", "RichText", "Text");
    /** Object field names Liferay reserves for its system fields. */
    private static final List<String> RESERVED_FIELD_NAMES = List.of(
        "status", "id", "companyId", "groupId", "userId", "userName", "createDate", "modifiedDate", "uuid", "externalReferenceCode");

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
                if (content.contains("type: objectAction")) {
                    String dir = path.substring(0, path.length() - "client-extension.yaml".length());
                    for (String required : new String[] {"Dockerfile", "LCP.json"}) {
                        if (!plan.generatedFiles.containsKey(dir + required)) {
                            warnings.add(path + " is a microservice client extension without " + dir + required + "; the workspace build fails with 'Required file \"" + required + "\" not found'.");
                        }
                    }
                }
                if (content.contains("host.docker.internal")) {
                    warnings.add(path + " hard-codes host.docker.internal; generate localhost and document the other environments in MIGRATION.md.");
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
                checkObjectActions(path, content, plan, warnings);
            }

            if (path.endsWith(".js") || path.endsWith(".jsx")) {
                checkApiPaths(path, content, warnings);
            }

            if (path.endsWith("_custom.scss") && Pattern.compile("(?m)^(?:body|html)\\b[^{]*\\{").matcher(content).find()) {
                warnings.add(path + " styles body/html at the top level, which also restyles Liferay's menus; scope the rules under #wrapper.");
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
        List<String> microservices = new ArrayList<>();
        for (String p : plan.generatedFiles.keySet()) {
            if (p.endsWith("client-extension.yaml") && plan.generatedFiles.get(p).contains("type: objectAction")) {
                microservices.add(Paths.get(p).getParent().getFileName().toString());
            }
        }
        if (!microservices.isEmpty()) {
            sb.append("   - Object Action microservices (deploy FIRST, then start them; the objects' actions call them):\n");
            for (String ceName : microservices) {
                sb.append("     blade gw :client-extensions:").append(ceName).append(":deploy\n");
                sb.append("     blade gw :client-extensions:").append(ceName).append(":bootRun\n");
            }
            sb.append("     Liferay reaches each microservice at the '.serviceAddress' of its client-extension.yaml (generated as localhost:<port>,\n");
            sb.append("     for a local Liferay bundle). Change it to match how Liferay runs:\n");
            sb.append("       * Liferay in Docker, microservice on the host: host.docker.internal:<port>\n");
            sb.append("         (on Linux, start the container with --add-host=host.docker.internal:host-gateway)\n");
            sb.append("       * Microservice as another Docker Compose service: <service-name>:<port>\n");
            sb.append("       * Remote Liferay: a host and port of the microservice that Liferay can reach\n");
            sb.append("       * Liferay SaaS / PaaS: deploy the zip; the platform sets the addresses (LCP.json)\n");
            sb.append("     The microservice reaches Liferay with com.liferay.lxc.dxp.* in application-default.properties (localhost:8080).\n");
        }
        sb.append("   - Batch Object Client Extensions (deploy BEFORE the frontend to initialize DB & REST APIs):\n");
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

        boolean templatesGenerated = plan.generatedFiles.values().stream()
            .anyMatch(c -> c.contains("com.liferay.notification.rest.dto.v1_0.NotificationTemplate"));
        if (templatesGenerated) {
            sb.append("\n6. NOTIFICATIONS (REPLACING LEGACY MAILSERVICE):\n");
            sb.append("   - The Batch Client Extension creates the Notification Templates and the Object Actions that send them.\n");
            sb.append("   - E-mails are only delivered with an SMTP server and a valid sender configured:\n");
            sb.append("     Control Panel -> Server Administration -> Mail (or the mail.session.* portal properties).\n");
            sb.append("   - Review the texts in Control Panel -> Notifications -> Templates, and after a test entry check\n");
            sb.append("     Control Panel -> Notifications -> Notification Queue.\n");
        } else if (!analysisResult.detectedNotifications.isEmpty()) {
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

    /** Flags object fields and actions that Liferay rejects or duplicates when the batch is deployed (validated on DXP 2026.q3). */
    private static void checkObjectActions(String path, String content, MigrationPlan plan, List<String> warnings) {
        if (content.contains("\"com.liferay.object.admin.rest.dto.v1_0.ObjectAction\"")) {
            warnings.add(path + " is a standalone ObjectAction batch file and was not written; declare the actions in the 'objectActions' array of the object definition.");
            return;
        }
        if (content.contains("\"com.liferay.notification.rest.dto.v1_0.NotificationTemplate\"")) {
            checkNotificationTerms(path, content, plan, warnings);
        }
        for (String field : arrayItems(content, "objectFields")) {
            if (!field.contains("\"externalReferenceCode\"")) {
                warnings.add(path + " has an object field without externalReferenceCode (" + nameOf(field) +
                    "); deploying the batch again fails with 'Duplicate name'.");
            }
            String businessType = topLevelString(field, "businessType");
            if (businessType != null && !BUSINESS_TYPES.contains(businessType)) {
                warnings.add(path + " field " + nameOf(field) + " has businessType '" + businessType +
                    "', which Liferay Objects does not have (valid: " + String.join(", ", BUSINESS_TYPES) + ").");
            }
            String fieldName = topLevelString(field, "name");
            if (RESERVED_FIELD_NAMES.contains(fieldName)) {
                warnings.add(path + " has an object field named '" + fieldName + "', a reserved name ('Reserved name " + fieldName +
                    "'); rename it with the entity prefix, e.g. '[entity]" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1) + "'.");
            }
        }
        for (String definition : arrayItems(content, "items")) {
            if (!arrayItems(definition, "objectActions").isEmpty()) {
                String erc = topLevelString(definition, "externalReferenceCode");
                int fields = arrayItems(definition, "objectFields").size();
                int expected = maxFieldCount(plan, erc);
                if (fields < expected) {
                    warnings.add(path + " repeats object " + erc + " with " + fields + " of its " + expected +
                        " fields to add its actions; repeat the complete definition (every field with the same externalReferenceCode).");
                }
            }
        }
        for (String action : arrayItems(content, "objectActions")) {
            String name = nameOf(action);
            if (!action.contains("\"externalReferenceCode\"")) {
                warnings.add(path + " has an object action without externalReferenceCode (" + name + "); it is duplicated on every deployment.");
            }
            String rawName = topLevelString(action, "name");
            if (rawName != null && !rawName.matches("[a-z][A-Za-z0-9]*")) {
                warnings.add(path + " action " + name + " must have a camelCase name with only letters and digits (e.g. 'notifyUserOnCreation'); put the readable text in 'label'.");
            }
            if ((action.contains("\"update-object-entry\"") || action.contains("\"add-object-entry\"")) &&
                !action.contains("\"objectDefinitionExternalReferenceCode\"")) {
                warnings.add(path + " action " + name + " needs 'objectDefinitionExternalReferenceCode' in its parameters (ObjectActionParametersException).");
            }
            if (action.matches("(?s).*\"objectActionTriggerKey\"\\s*:\\s*\"onBefore.*")) {
                warnings.add(path + " action " + name + " uses an onBefore* trigger, which Object Actions do not have; use an onAfter* trigger or an Object Validation.");
            }
            Matcher function = Pattern.compile("\"function#([\\w.-]+)\"").matcher(action);
            if (function.find() && !definesObjectAction(plan, function.group(1))) {
                warnings.add(path + " action " + name + " calls function#" + function.group(1) +
                    ", but no generated client-extension.yaml defines that objectAction; deploy it before the batch.");
            }
        }
    }

    /** Template terms are [%OBJECTNAME_FIELDNAME%] built from the object's name and its own fields (validated on DXP 2026.q3). */
    private static void checkNotificationTerms(String path, String content, MigrationPlan plan, List<String> warnings) {
        for (String template : arrayItems(content, "items")) {
            String objectErc = topLevelString(template, "objectDefinitionExternalReferenceCode");
            String objectName = null;
            java.util.Set<String> validTerms = new java.util.TreeSet<>();
            for (String definition : definitions(plan)) {
                if (objectErc != null && objectErc.equals(topLevelString(definition, "externalReferenceCode"))) {
                    objectName = topLevelString(definition, "name");
                    for (String field : arrayItems(definition, "objectFields")) {
                        validTerms.add("[%" + objectName.toUpperCase() + "_" + topLevelString(field, "name").toUpperCase() + "%]");
                    }
                }
            }
            if (objectName == null) {
                continue;
            }
            java.util.Set<String> wrong = new java.util.TreeSet<>();
            Matcher terms = Pattern.compile("\\[%[A-Z0-9_]+%\\]").matcher(template);
            while (terms.find()) {
                if (!validTerms.contains(terms.group())) {
                    wrong.add(terms.group());
                }
            }
            if (!wrong.isEmpty()) {
                warnings.add(path + " uses terms that are not fields of object " + objectName + ": " + String.join(", ", wrong) +
                    " (Liferay sends them as plain text). Valid terms: " + String.join(", ", validTerms) + ".");
            }
        }
    }

    /** The ObjectDefinition items of every batch file in the plan. */
    private static List<String> definitions(MigrationPlan plan) {
        List<String> definitions = new ArrayList<>();
        for (Map.Entry<String, String> e : plan.generatedFiles.entrySet()) {
            if (e.getKey().endsWith(".batch-engine-data.json") &&
                e.getValue().contains("\"com.liferay.object.admin.rest.dto.v1_0.ObjectDefinition\"")) {
                definitions.addAll(arrayItems(e.getValue(), "items"));
            }
        }
        return definitions;
    }

    private static int maxFieldCount(MigrationPlan plan, String erc) {
        int max = 0;
        for (String definition : definitions(plan)) {
            if (erc != null && erc.equals(topLevelString(definition, "externalReferenceCode"))) {
                max = Math.max(max, arrayItems(definition, "objectFields").size());
            }
        }
        return max;
    }

    private static boolean definesObjectAction(MigrationPlan plan, String id) {
        Pattern entry = Pattern.compile("(?m)^" + Pattern.quote(id) + ":\\s*$");
        for (Map.Entry<String, String> e : plan.generatedFiles.entrySet()) {
            if (e.getKey().endsWith("client-extension.yaml") && e.getValue().contains("type: objectAction") &&
                entry.matcher(e.getValue()).find()) {
                return true;
            }
        }
        return false;
    }

    /** The top-level objects of every JSON array named key, found by brace depth (field and action JSON has no braces in strings). */
    private static List<String> arrayItems(String content, String key) {
        List<String> items = new ArrayList<>();
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[").matcher(content);
        while (m.find()) {
            int depth = 0;
            int start = -1;
            for (int i = m.end(); i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{' || c == '[') {
                    if (depth++ == 0 && c == '{') {
                        start = i;
                    }
                } else if (c == '}' || c == ']') {
                    if (depth == 0) {
                        break;
                    }
                    if (--depth == 0 && c == '}') {
                        items.add(content.substring(start, i + 1));
                    }
                }
            }
        }
        return items;
    }

    /** The item's own "name", ignoring the ones nested in its parameters (e.g. predefinedValues). */
    private static String nameOf(String item) {
        String name = topLevelString(item, "name");
        return name != null ? "'" + name + "'" : "unnamed";
    }

    /** A string property of the item itself, not of the objects or arrays nested in it. */
    private static String topLevelString(String item, String key) {
        StringBuilder topLevel = new StringBuilder();
        int depth = 0;
        for (char c : item.toCharArray()) {
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            } else if (depth == 1) {
                topLevel.append(c);
            }
        }
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(topLevel);
        return m.find() ? m.group(1) : null;
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
