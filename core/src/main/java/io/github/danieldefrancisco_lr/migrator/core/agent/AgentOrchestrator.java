package io.github.danieldefrancisco_lr.migrator.core.agent;

import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleAnalysisResult;
import io.github.danieldefrancisco_lr.migrator.core.output.ThemeGenerator;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coordinates Donatello, Leonardo, Michelangelo, and Raphael to execute the migration via Gemini.
 */
public class AgentOrchestrator {

    private final LlmClient geminiClient;

    public AgentOrchestrator(LlmClient geminiClient) {
        this.geminiClient = geminiClient;
    }

    public static class MigrationPlan {
        public String architectureBlueprint;
        public Map<String, String> generatedFiles = new HashMap<>();
        /** Files copied as they are from the legacy module: workspace-relative destination -> absolute source path. */
        public Map<String, String> copiedFiles = new java.util.TreeMap<>();
    }

    public MigrationPlan planAndGenerate(ModuleAnalysisResult analysis, String workspaceRoot) throws Exception {
        return planAndGenerate(analysis, workspaceRoot, true);
    }

    public MigrationPlan planAndGenerate(ModuleAnalysisResult analysis, String workspaceRoot, boolean generateBackendObjects) throws Exception {
        MigrationPlan plan = new MigrationPlan();

        // Prefer Liferay Workspace Root if detected
        String rootDir = analysis.liferayWorkspaceRoot != null ? analysis.liferayWorkspaceRoot : workspaceRoot;

        // 1. Prepare Donatello's Extracted Context
        StringBuilder legacyContext = new StringBuilder();
        legacyContext.append("### Legacy Module Analysis:\n");
        legacyContext.append("Module Name: ").append(analysis.moduleName).append("\n");
        legacyContext.append("Source Liferay Platform Version: ").append(analysis.sourceLiferayVersion != null ? analysis.sourceLiferayVersion : "Liferay 7.x").append("\n");
        legacyContext.append("Entities Detected: ").append(String.join(", ", analysis.detectedEntities)).append("\n");
        legacyContext.append("Portlets Detected: ").append(String.join(", ", analysis.detectedPortlets)).append("\n");
        legacyContext.append("Listeners Detected: ").append(String.join(", ", analysis.detectedListeners)).append("\n");
        legacyContext.append("Schedulers Detected: ").append(String.join(", ", analysis.detectedSchedulers)).append("\n");
        legacyContext.append("Notifications Detected: ").append(String.join(", ", analysis.detectedNotifications)).append("\n\n");
        if (!analysis.detectedCustomLogic.isEmpty()) {
            legacyContext.append("CUSTOM LOGIC TO PORT (one line per method, with the Object Action trigger it maps to; the code is in the files below):\n");
            for (String unit : analysis.detectedCustomLogic) {
                legacyContext.append("- ").append(unit).append("\n");
            }
            legacyContext.append("Decide for EACH line (mapping playbook 3.1): 'logging only' -> entry history, no action; logic a native action can express\n" +
                "(set fields, create an entry, e-mail, plain HTTP POST) -> native Object Action; anything else (calculations, conditions on several fields,\n" +
                "calls to external systems with custom payloads, several steps) -> the Spring Boot objectAction microservice; 'query' methods -> REST\n" +
                "filters in the frontend; validations that throw -> Object Validations. Name every decision in the blueprint.\n\n");
        }
        if (analysis.theme != null) {
            legacyContext.append("THEME INVENTORY (the migrator writes the theme client extension '" + ThemeGenerator.slug(analysis.theme.themeId) +
                "' itself, without AI: theme CSS from the theme's SCSS, its Style Book tokens, images and JavaScript. Do NOT generate it;\n" +
                "plan and generate only the header/footer fragments and master page that replace its FreeMarker templates and settings):\n");
            for (String line : analysis.theme.summary()) {
                legacyContext.append("- ").append(line).append("\n");
            }
            legacyContext.append("\n");
        }
        String renames = reservedColumnRenames(analysis);
        if (!renames.isEmpty()) {
            legacyContext.append("REQUIRED FIELD RENAMES (these service.xml columns use names Liferay Objects reserve; use the new names everywhere: objectFields, API CONTRACT, frontend, notification terms):\n")
                .append(renames).append("\n");
        }

        for (Map.Entry<String, String> entry : analysis.rawSourceExtracts.entrySet()) {
            legacyContext.append("--- File: ").append(entry.getKey()).append(" ---\n");
            String content = entry.getValue();
            if (content.length() > 6000) {
                content = content.substring(0, 6000) + "\n[...truncated for context efficiency...]";
            }
            legacyContext.append(content).append("\n\n");
        }

        // 2. Load Leonardo and Michelangelo Playbooks (Local workspace override OR bundled in plugin JAR)
        String mappingPlaybook = loadKnowledgeContent(rootDir, "playbooks/02-mapping-rules.md");
        String generationPlaybook = loadKnowledgeContent(rootDir, "playbooks/03-generation-rules.md");
        String batchTemplate = loadKnowledgeContent(rootDir, "schemas/batch-object-definition.template.json");

        // Blueprints for the migration patterns found in this module; Leonardo applies them in the plan
        StringBuilder blueprints = new StringBuilder();
        if (generateBackendObjects && (analysis.hasServiceBuilder || !analysis.detectedEntities.isEmpty())) {
            blueprints.append("\n\n").append(loadKnowledgeContent(rootDir, "blueprints/data-migration-strategy.md"));
        }
        if (!analysis.detectedNotifications.isEmpty()) {
            blueprints.append("\n\n").append(loadKnowledgeContent(rootDir, "blueprints/notification-and-workflow-migration.md"));
        }
        if (analysis.hasTheme) {
            blueprints.append("\n\n").append(loadKnowledgeContent(rootDir, "blueprints/theme-and-widget-template-migration.md"));
        }

        // 3. Invoke Leonardo (Architect)
        String backendEntityGuidance = generateBackendObjects ?
            "USER PREFERENCE: The user requested to ALSO generate Batch Client Extensions (Liferay Objects) for any backend entities used/referenced by this module (e.g. " + String.join(", ", analysis.detectedEntities) + "). Generate the Object Definition batch files and client-extension.yaml so that the Custom Element has an active backend API (/o/c/[slug])." :
            "USER PREFERENCE: The user chose NOT to generate backend Objects. Assume the Liferay Objects already exist or are migrated separately. DO NOT generate any Batch Client Extension. Focus strictly on generating the Frontend Custom Element Client Extension (calling /o/c/[slug]).";

        String leonardoSystemInstruction = "You are Leonardo, the Lead Architect for modernizing legacy Liferay 7.x OSGi modules into modern Liferay Objects and Client Extensions.\n" +
            "The source platform is: " + analysis.sourceLiferayVersion + ".\n" +
            "Rigorously follow the architectural mapping rules below:\n\n" + mappingPlaybook +
            (blueprints.length() == 0 ? "" :
                "\n\n### MIGRATION BLUEPRINTS FOR THIS MODULE\n" +
                "Apply these blueprints to the matching parts of the module." + blueprints);

        String leonardoPrompt = "Analyze the following legacy OSGi module extract and produce a comprehensive modernization blueprint.\n" +
            backendEntityGuidance + "\n\n" +
            "Determine:\n" +
            "1. Liferay Object definitions (ignoring audit fields like companyId, userId, createDate), ONLY if backend objects are requested.\n" +
            "2. Required Batch Client Extension structure, ONLY if backend objects are requested.\n" +
            "3. Frontend UI components (React JavaScript Custom Elements or Page Fragments), if portlets exist.\n" +
            "4. Model Listeners / Service Wrappers / custom LocalServiceImpl logic mapping (Native Object Actions vs a Spring Boot objectAction client extension),\n" +
            "   naming for each action: the object, the trigger, the executor, and the legacy class and method it replaces.\n" +
            "5. API CONTRACT (ALWAYS include it, even if backend objects are not generated): a section titled '## API CONTRACT' listing,\n" +
            "   for each Liferay Object: its name (PascalCase, e.g. DevconRegistration), its externalReferenceCode, and its REST endpoint\n" +
            "   '/o/c/' + the lowercase plural of the name with only letters and digits (e.g. '/o/c/devconregistrations');\n" +
            "   for each Picklist: its externalReferenceCode and its entries URL\n" +
            "   '/o/headless-admin-list-type/v1.0/list-type-definitions/by-external-reference-code/[ERC]/list-type-entries'.\n" +
            "   for each Object Action: its name, externalReferenceCode, trigger and executor; for each Notification Template: its externalReferenceCode;\n" +
            "   for each microservice: its client extension id and port.\n" +
            "   The backend and frontend coders work in parallel and never see each other's files: this contract is the ONLY way they agree on names, codes and URLs.\n\n" +
            "FORMAT: do NOT use Markdown tables anywhere in the blueprint; use headings and bullet lists (e.g. '- `title` (String, Text, required)'). Never draw lines of dashes.\n" +
            "Describe the design in prose and tables: NEVER write file contents (JSON, YAML, Java, JavaScript) in the blueprint; the coders write the files from the playbook templates.\n" +
            "Legacy columns with a reserved name (status, id, companyId, groupId, userId, userName, createDate, modifiedDate, uuid, externalReferenceCode) get a new field name prefixed with the entity (e.g. 'status' -> 'bookStatus'); list the rename in the API CONTRACT.\n" +
            "MICROSERVICE: plan at most ONE Spring Boot objectAction client extension per module ('[module-slug]-object-action', port 58081), with one Object Action per trigger;\n" +
            "logic of the same trigger runs in order inside it (e.g. compute the late fee, then send it to the inventory). Its OAuth scopes include 'c_[objectname].everything' (lowercase) when it updates entries.\n" +
            "'update-object-entry' only sets fixed values: computed values (dates such as 'today + 14 days', arithmetic, conditions on the previous value) go to the microservice.\n\n" +
            legacyContext.toString();

        String blueprint;
        try {
            blueprint = geminiClient.generateContent(leonardoSystemInstruction, leonardoPrompt);
        } catch (Exception e) {
            throw new RuntimeException("[Phase 1 - Leonardo Architect Blueprinting]: " + e.getMessage(), e);
        }
        blueprint = collapseDashRuns(blueprint);
        plan.architectureBlueprint = blueprint;

        // 4. Invoke Michelangelo (Coder) via Parallel Workers (Strategy A)
        boolean needBackend = generateBackendObjects && (!analysis.detectedEntities.isEmpty() || analysis.hasServiceBuilder);
        boolean needFrontend = analysis.hasJspPortlet || !analysis.detectedPortlets.isEmpty();

        // A theme only needs the fragments that replace its templates (its client extension needs no AI)
        boolean themeOnly = analysis.theme != null && !needBackend && !needFrontend;
        if (themeOnly) {
            needFrontend = true;
        }
        // Fallback: If neither was strictly flagged, run both so nothing is missed
        if (!needBackend && !needFrontend) {
            needBackend = generateBackendObjects;
            needFrontend = true;
        }

        CompletableFuture<String> backendFuture = null;
        CompletableFuture<String> frontendFuture = null;

        if (needBackend) {
            String backendInstruction = "You are Michelangelo (Backend Specialist), specialized in Liferay Batch Client Extensions, Object Folders, Object Definitions, and Picklists.\n" +
                "Follow the generation playbook rules below:\n\n" + generationPlaybook + "\n\n" +
                "### CANONICAL BATCH OBJECT TEMPLATE:\n" + batchTemplate + "\n\n" +
                "### STRICT BACKEND RULES:\n" +
                "1. Generate ONLY Batch Client Extension files under 'client-extensions/[entity-slug]-object/':\n" +
                "   - client-extensions/[entity-slug]-object/client-extension.yaml\n" +
                "   - client-extensions/[entity-slug]-object/batch/01-00-folder-definition.batch-engine-data.json\n" +
                "   - client-extensions/[entity-slug]-object/batch/01-01-[entity-slug]-picklists.batch-engine-data.json (if select/dropdown fields exist)\n" +
                "   - client-extensions/[entity-slug]-object/batch/01-02-[entity-slug]-definition.batch-engine-data.json\n" +
                "   - client-extensions/[entity-slug]-object/batch/01-03-[entity-slug]-notification-template.batch-engine-data.json and 01-04-[entity-slug]-actions.batch-engine-data.json (only for notification actions, playbook section 4.3)\n" +
                "   - client-extensions/[logic-slug]-object-action/ (only if the blueprint plans a microservice: every file of playbook section 4.4, including Dockerfile and LCP.json)\n" +
                "   All field constraints (required, unique) belong directly on objectFields inside 01-02. NEVER generate a batch file with className ObjectAction or ObjectValidationRule.\n" +
                "2. DO NOT generate package.json, React components, or frontend files in this call!\n" +
                "3. In 01-00-folder-definition: 'name' MUST be alphanumeric with NO SPACES (e.g. 'DevconMigrationObjects').\n" +
                "4. NEVER include reserved system field names ('status', 'id', 'companyId', 'groupId', 'userId', 'userName', 'createDate', 'modifiedDate', 'uuid', 'externalReferenceCode') in objectFields, even when the legacy service.xml has that column: rename it with the entity prefix (e.g. 'status' -> 'bookStatus'), as in the blueprint's API CONTRACT. Deployment fails with 'Reserved name status'!\n" +
                "5. NEVER include 'objectLayouts' or 'objectViews' inside ObjectDefinition items! Liferay creates layouts automatically.\n" +
                "6. NEVER add inline 'options' inside objectFieldSettings! For dropdowns/select fields, generate a Picklist in 01-01 and reference it via 'listTypeDefinitionExternalReferenceCode'!\n" +
                "7. In Picklist listTypeEntries: 'key' MUST be alphanumeric with NO spaces, underscores, or hyphens (e.g. 'inPerson', 'M').\n" +
                "8. In client-extension.yaml, include scopes: Liferay.Headless.Batch.Engine.everything, Liferay.Object.Admin.REST.everything, Liferay.Headless.Admin.List.Type.everything (plus Liferay.Notification.REST.everything when there are Notification Templates).\n" +
                "9. Use EXACTLY the object names and the object and picklist externalReferenceCodes from the blueprint's API CONTRACT: the frontend calls them by those names.\n" +
                "10. EVERY objectField and EVERY objectAction MUST have its own fixed externalReferenceCode ('FIELD_[ENTITY]_[FIELDNAME]', 'ACTION_[ENTITY]_[ACTIONNAME]', playbook 2.1). Without them the next deployment fails with 'Duplicate name'.\n" +
                "11. Object Actions go ONLY in the 'objectActions' array of the ObjectDefinition item (playbook section 4.2). 'update-object-entry' and 'add-object-entry' MUST include 'objectDefinitionExternalReferenceCode' in their parameters. Valid triggers: onAfterAdd, onAfterUpdate, onAfterDelete, standalone (there is no onBefore* trigger).\n" +
                "12. Object Action 'name' is camelCase with only letters and digits (e.g. 'notifyUserOnLoanCreation'); the readable text goes in 'label'. A 'standalone' action MUST also have \"errorMessage\": {\"en_US\": \"...\"}.\n" +
                "13. The '01-04-[entity]-actions' file repeats the COMPLETE object definition of 01-02 (all its properties and every objectField with the same externalReferenceCodes) plus 'objectActions'. Never send only the ERC and name.\n" +
                "14. Notification Template terms are '[%' + the object's 'name' in UPPERCASE (NOT its externalReferenceCode) + '_' + the field 'name' in UPPERCASE without underscores + '%]': object 'Loan', field 'dueDate' -> '[%LOAN_DUEDATE%]'. Use only fields of that object (no fields of related objects or users, e.g. NOT '[%LOAN_BORROWERUSER_EMAILADDRESS%]'); the recipient 'to' is a Text email field of the object, e.g. '[%LOAN_BORROWEREMAIL%]' (add that field if the legacy code e-mails a user).\n" +
                "15. A microservice action uses 'objectActionExecutorKey': 'function#[logic-slug]-object-action'. In its client-extension.yaml, '.serviceAddress' is ALWAYS 'localhost:[port]' and application-default.properties points to 'localhost:8080': never hard-code host.docker.internal or another host; the other environments are documented in MIGRATION.md.\n" +
                "    Generate ONE microservice per migrated module, serving all its actions (branch on 'objectActionTriggerKey'): logic of the same trigger runs in order in one call (e.g. compute the late fee, then send it to the inventory). Port 58081 (58082, 58083... only for further modules); the same port in '.serviceAddress', 'server.port' and LCP.json.\n" +
                "    Call Liferay only with Spring's RestClient and the JWT, as in playbook 4.4.5: the only Liferay classes are BaseRestController and ClientExtensionUtilSpringBootComponentScan (no Liferay Java clients such as com.liferay.object.rest.client).\n" +
                "    Dates (playbook 4.4.6): read them from objectEntryDTO[ObjectName].properties; write DateTime values as Instant.now().truncatedTo(ChronoUnit.SECONDS).toString() and Date values as LocalDate.toString() (fractions of a second make Liferay answer 500).\n" +
                "    To call a custom object's REST API, the OAuth scope is 'c_' + the object name in lowercase + '.everything' (object 'Loan' -> 'c_loan.everything'), never 'C_Loan' or 'Liferay.Object.C_Loan'.\n" +
                "16. 'businessType' is one of: Text, LongText, RichText, Integer, LongInteger, Decimal, PrecisionDecimal, Boolean, Date, DateTime, Picklist, MultiselectPicklist, Attachment, Relationship. There is NO 'User' business type: a user id is a LongInteger.\n" +
                "17. 'conditionExpression' can only use the entry's current field values. Conditions on the previous value (e.g. status changed to 'returned') go in the microservice, which receives 'originalObjectEntry'.\n" +
                "    'update-object-entry' predefinedValues are fixed values ('inputAsValue': true). NEVER write Java or JavaScript there ('new Date()', getTime()...): computed values go to the microservice.\n" +
                "18. NEVER generate ObjectEntry batch files: you cannot see the legacy data, so any entries are invented (the migrator discards them). A data migration is described in the blueprint and MIGRATION.md as an export of the legacy table.\n" +
                "19. Do not generate ObjectValidationRule batch files: they are not deployed. List each validation (field, rule, error message, legacy method) in the blueprint so MIGRATION.md tells the administrator to create it.\n";

            String backendPrompt = "Based on Leonardo's blueprint below, generate the complete, ready-to-deploy Batch Client Extension files.\n" +
                "Output each file clearly delimited with '=== FILE: [relative/path] ===' and '=== END FILE ==='.\n\n" +
                "Blueprint:\n" + blueprint + "\n\n" +
                "Legacy Context:\n" + legacyContext.toString();

            backendFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return geminiClient.generateContent(backendInstruction, backendPrompt);
                } catch (Exception e) {
                    throw new RuntimeException("[Michelangelo Backend Worker]: " + e.getMessage(), e);
                }
            });
        }

        if (needFrontend && themeOnly) {
            String themeSlug = ThemeGenerator.slug(analysis.theme.themeId);
            String themeInstruction = "You are Michelangelo (Frontend Specialist), specialized in Liferay Page Fragments that replace classic theme templates.\n" +
                "Follow the generation playbook rules below:\n\n" + generationPlaybook + "\n\n" +
                "### STRICT THEME FRAGMENT RULES:\n" +
                "1. Generate ONLY the fragment collection of playbook section 9.2, under 'fragments/" + themeSlug + "-fragments/': collection.json,\n" +
                "   a header fragment and a footer fragment (plus a separate fragment only for a clearly separate bar of the template), each with\n" +
                "   fragment.json, index.html, index.css (and configuration.json, index.js when needed).\n" +
                "2. DO NOT generate client-extensions/, the theme CSS, client-extension.yaml, package.json, React or batch files: the migrator writes the theme client extension.\n" +
                "3. Keep the template's HTML structure, CSS classes and ids, so the migrated theme CSS styles the fragments. index.css only holds what\n" +
                "   the fragment needs beyond the theme CSS (usually nothing; leave it empty rather than copying theme styles).\n" +
                "4. Replace the template's FreeMarker as in the playbook 9.2 table: editable fields for texts, logo and links; <lfr-drop-zone></lfr-drop-zone>\n" +
                "   for navigation, search, user bar, language selector and embedded portlets; nothing for control menu, includes, <html>/<head>/<body> and the content area.\n" +
                "5. Fragment FreeMarker uses square brackets ([#if configuration.x]...[/#if]); never <#if>, ${theme_display}, nav_items or theme variables.\n" +
                "6. Each configurable theme setting the template uses becomes a configuration.json field (checkbox -> \"type\": \"checkbox\" with no dataType: \"dataType\": \"bool\" is rejected).\n" +
                "7. index.js: only plain DOM code using fragmentElement and configuration; no AUI.\n" +
                "8. collection.json has \"fragmentCollectionKey\" (the collection folder name) and every fragment.json \"fragmentEntryKey\" (its folder name).\n" +
                "9. EVERY drop zone has a stable id (<lfr-drop-zone data-lfr-drop-zone-id=\"navigation\">) and NO drop zone or editable field is inside [#if]:\n" +
                "   hide optional parts with a class instead (class=\"language-selector[#if !configuration.showLanguageSelector] d-none[/#if]\"). Otherwise\n" +
                "   Liferay matches drop zones by position and the widgets move to another zone when an option changes.\n" +
                "10. Only settings that change this fragment's markup become configuration; portal-behaviour settings (use-sign-in-modal, wrap-widget-page-content...) go to the blueprint for MIGRATION.md.\n";

            String themePrompt = "Based on Leonardo's blueprint below, generate the fragment collection that replaces the theme templates.\n" +
                "Output each file clearly delimited with '=== FILE: [relative/path] ===' and '=== END FILE ==='.\n\n" +
                "Blueprint:\n" + blueprint + "\n\n" +
                "Legacy Context:\n" + legacyContext.toString();

            frontendFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return geminiClient.generateContent(themeInstruction, themePrompt);
                } catch (Exception e) {
                    throw new RuntimeException("[Michelangelo Theme Fragments Worker]: " + e.getMessage(), e);
                }
            });
        } else if (needFrontend) {
            String frontendInstruction = "You are Michelangelo (Frontend Specialist), specialized in Liferay React Custom Element Client Extensions and Page Fragments.\n" +
                "Follow the generation playbook rules below:\n\n" + generationPlaybook + "\n\n" +
                "### STRICT FRONTEND RULES:\n" +
                "1. Generate ONLY React JavaScript Custom Element files under 'client-extensions/[portlet-slug]-custom-element/' (and Page Fragments under 'fragments/[collection-slug]/' if blueprinted):\n" +
                "   - client-extensions/[portlet-slug]-custom-element/client-extension.yaml\n" +
                "   - client-extensions/[portlet-slug]-custom-element/package.json\n" +
                "   - client-extensions/[portlet-slug]-custom-element/public/index.html\n" +
                "   - client-extensions/[portlet-slug]-custom-element/src/index.js\n" +
                "   - client-extensions/[portlet-slug]-custom-element/src/App.js\n" +
                "   - client-extensions/[portlet-slug]-custom-element/src/index.css\n" +
                "   - client-extensions/[portlet-slug]-custom-element/src/components/Form.js (or RegistrationForm.js)\n" +
                "   - client-extensions/[portlet-slug]-custom-element/src/components/ListView.js (or ConfirmationView.js)\n" +
                "   - client-extensions/[portlet-slug]-custom-element/src/services/liferayApi.js\n" +
                "2. DO NOT generate batch JSON files or Object definitions in this call!\n" +
                "3. ALWAYS use plain JavaScript (.js, .jsx). NEVER use TypeScript (.ts, .tsx)!\n" +
                "4. package.json MUST use react-scripts (\"start\": \"react-scripts start\", \"build\": \"react-scripts build\").\n" +
                "5. client-extension.yaml MUST assemble from 'build/static' into 'static', urls: ['js/main.*.js'], cssURLs: ['css/main.*.css'].\n" +
                "   Use EXACTLY this Liferay format: top-level keys are 'assemble' and the client extension id, each with its properties nested below:\n" +
                "   assemble:\n" +
                "     - from: build/static\n" +
                "       into: static\n" +
                "   [portlet-slug]-custom-element:\n" +
                "     cssURLs:\n" +
                "       - css/main.*.css\n" +
                "     friendlyURLMapping: [portlet-slug]\n" +
                "     htmlElementName: [portlet-slug]\n" +
                "     instanceable: true\n" +
                "     name: '[Portlet Name] Custom Element'\n" +
                "     portletCategoryName: category.client-extensions\n" +
                "     type: customElement\n" +
                "     urls:\n" +
                "       - js/main.*.js\n" +
                "     useESM: true\n" +
                "   NEVER use Kubernetes-style 'apiVersion', 'kind', 'metadata' or 'spec' keys! Liferay's Gradle plugin rejects them with a ClassCastException (TextNode cannot be cast to ObjectNode).\n" +
                "6. DO NOT import @clayui/* via npm! Use standard HTML elements with Liferay native Clay CSS classes (container, form-group, form-control, btn btn-primary, table table-autofit).\n" +
                "7. In entity lists/tables: EVERY row MUST provide working onClick handlers for Edit and Delete (never an unwired static Actions button)!\n" +
                "8. In src/services/liferayApi.js and src/App.js: Liferay Objects REST endpoints are ALWAYS lowercase alphanumeric with NO HYPHENS! (e.g. '/o/c/devconregistrations', NOT '/o/c/devcon-registrations').\n" +
                "   Take every endpoint VERBATIM from the blueprint's API CONTRACT and declare it as a constant (e.g. const OBJECT_PATH = '/o/c/devconregistrations').\n" +
                "   NEVER pass an externalReferenceCode (e.g. 'DEVCON_REGISTRATION') where a URL path is expected! Picklist URLs MUST start with '/o/headless-admin-list-type/v1.0/'.\n" +
                "9. In src/services/liferayApi.js: Always use normalizeUrl(path) and merge 'Content-Type': 'application/json' and 'Accept': 'application/json'.\n" +
                "10. If generating Page Fragments, put them in 'fragments/[collection-slug]/': collection.json, [fragment-slug]/fragment.json (with 'fragmentEntryKey'), index.html, index.css. Never in 'client-extensions/'!\n" +
                "11. In React form state and API payloads: NEVER include 'status' (e.g. status: 0)! In Liferay Objects, status is a system-managed workflow state. Sending 'status' in POST/PUT causes 'Unable to map JSON path: status' HTTP 400 error! In liferayApi.js, createObjectEntry and updateObjectEntry MUST defensively strip 'status', 'id', 'creator', 'dateCreated', and 'dateModified' before sending!\n" +
                "12. In React dropdowns (<select>) and radio options: Option values MUST MATCH Picklist entry keys EXACTLY! In Liferay, Picklist keys are alphanumeric (e.g. 'INPERSON', 'VIRTUAL', 'S', 'M'). NEVER use underscores like 'IN_PERSON' if the Picklist key is 'INPERSON', or Liferay will reject with 'not mapped to a valid list type entry'!\n" +
                "13. Liferay API responses return Picklist fields as objects {\"key\": \"VIRTUAL\", \"name\": \"Virtual\"}, even though you send just the key! When rendering a picklist value from an API response (lists, detail and confirmation views), render value?.name ?? value, NEVER the object itself, or React crashes with error #31 (Objects are not valid as a React child).\n" +
                "14. Call ONLY the Liferay endpoints listed in the playbook's 'Liferay Objects REST API' table (section 3.7). There is NO '/count', '/search', '/list' or '/all' endpoint! To count entries use countObjectEntries(), which reads 'totalCount' from GET [endpoint]?page=1&pageSize=1.\n";

            String frontendPrompt = "Based on Leonardo's blueprint below, generate the complete, ready-to-deploy React Custom Element files.\n" +
                "Output each file clearly delimited with '=== FILE: [relative/path] ===' and '=== END FILE ==='.\n\n" +
                "Blueprint:\n" + blueprint + "\n\n" +
                "Legacy Context:\n" + legacyContext.toString();

            frontendFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return geminiClient.generateContent(frontendInstruction, frontendPrompt);
                } catch (Exception e) {
                    throw new RuntimeException("[Michelangelo Frontend Worker]: " + e.getMessage(), e);
                }
            });
        }

        // Wait for all active workers to complete concurrently
        List<CompletableFuture<String>> futures = new ArrayList<>();
        if (backendFuture != null) futures.add(backendFuture);
        if (frontendFuture != null) futures.add(frontendFuture);

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("[Phase 2 - Parallel Code Generation]: " + cause.getMessage(), cause);
        }

        // Merge generated files from all workers
        if (backendFuture != null) {
            parseGeneratedFiles(backendFuture.join(), plan.generatedFiles);
        }
        if (frontendFuture != null) {
            parseGeneratedFiles(frontendFuture.join(), plan.generatedFiles);
        }

        // The theme client extension is built without AI from the theme's own files (it replaces any the coders wrote)
        if (analysis.theme != null) {
            String themeDir = ThemeGenerator.slug(analysis.theme.themeId);
            plan.generatedFiles.keySet().removeIf(p -> p.startsWith("client-extensions/" + themeDir + "/"));
            ThemeGenerator.generate(analysis.theme, plan);
        }

        return plan;
    }

    /** Object field names Liferay reserves; legacy columns with these names must be renamed. */
    private static final List<String> RESERVED_FIELD_NAMES = List.of(
        "status", "id", "companyId", "groupId", "userId", "userName", "createDate", "modifiedDate", "uuid", "externalReferenceCode");

    private static final Pattern SB_ENTITY = Pattern.compile("(?s)<entity\\b[^>]*\\bname=\"(\\w+)\"(.*?)</entity>");
    private static final Pattern SB_COLUMN = Pattern.compile("<column\\b[^>]*\\bname=\"(\\w+)\"");

    /**
     * Lists the service.xml columns whose names Liferay Objects reserve, with their new names (status -> bookStatus).
     * Gemini ignores the generic rule when the legacy column is called 'status', so the rename is spelled out per column.
     * Audit columns (companyId, userId, createDate...) are dropped by the playbook instead, so only the others are listed.
     */
    public static String reservedColumnRenames(ModuleAnalysisResult analysis) {
        java.util.Set<String> dropped = java.util.Set.of("companyId", "groupId", "userId", "userName", "createDate", "modifiedDate", "uuid");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> file : analysis.rawSourceExtracts.entrySet()) {
            if (!file.getKey().endsWith("service.xml")) {
                continue;
            }
            Matcher entity = SB_ENTITY.matcher(file.getValue());
            while (entity.find()) {
                String entityName = entity.group(1);
                Matcher column = SB_COLUMN.matcher(entity.group(2));
                while (column.find()) {
                    String name = column.group(1);
                    if (RESERVED_FIELD_NAMES.contains(name) && !dropped.contains(name)) {
                        String prefix = Character.toLowerCase(entityName.charAt(0)) + entityName.substring(1);
                        sb.append("- ").append(entityName).append(".").append(name).append(" -> ")
                            .append(prefix).append(Character.toUpperCase(name.charAt(0))).append(name.substring(1)).append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    /** A Markdown table separator row or a horizontal rule: only dashes, pipes, colons and spaces. */
    private static final Pattern SEPARATOR_LINE = Pattern.compile("(?m)^[|: \\t]*-{4,}[-|: \\t]*$");

    /**
     * Gemini sometimes fills Markdown table separator rows with hundreds of dashes (a repetition loop).
     * Reduces every run of dashes in such lines to '---', which renders the same.
     */
    public static String collapseDashRuns(String text) {
        Matcher m = SEPARATOR_LINE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group().replaceAll("-{4,}", "---")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Parses files delimited by '=== FILE: path ===' and '=== END FILE ===' into targetMap. */
    public static void parseGeneratedFiles(String rawOutput, Map<String, String> targetMap) {
        String[] blocks = rawOutput.split("=== FILE: ");
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            int newlineIdx = block.indexOf("\n");
            if (newlineIdx != -1) {
                String filePath = block.substring(0, newlineIdx).trim();

                // Clean up any trailing '===', backticks, or quotes
                while (filePath.endsWith("=") || filePath.endsWith("`") || filePath.endsWith("\"") || filePath.endsWith(" ")) {
                    filePath = filePath.substring(0, filePath.length() - 1).trim();
                }
                while (filePath.startsWith("`") || filePath.startsWith("\"")) {
                    filePath = filePath.substring(1).trim();
                }

                int endIdx = block.indexOf("=== END FILE ===");
                String content;
                if (endIdx != -1) {
                    content = block.substring(newlineIdx + 1, endIdx).trim();
                } else {
                    content = block.substring(newlineIdx + 1).trim();
                }

                if (!filePath.isEmpty()) {
                    targetMap.put(filePath, content);
                }
            }
        }
    }

    private String loadKnowledgeContent(String workspaceRoot, String relativePath) {
        // 1. Check local workspace for custom override
        if (workspaceRoot != null) {
            Path p = Paths.get(workspaceRoot, "migration-knowledge", relativePath);
            if (Files.exists(p)) {
                try {
                    return Files.readString(p, StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
            }
            Path directP = Paths.get(workspaceRoot, relativePath);
            if (Files.exists(directP)) {
                try {
                    return Files.readString(directP, StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
            }
        }

        // 2. Load from plugin bundle classpath
        String resourcePath = "/knowledge/" + relativePath;
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}

        // Fallback
        return "Follow standard Liferay DXP 7.4+ Client Extension and Object guidelines.";
    }
}
