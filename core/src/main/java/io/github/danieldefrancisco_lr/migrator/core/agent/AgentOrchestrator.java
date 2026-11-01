package io.github.danieldefrancisco_lr.migrator.core.agent;

import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleAnalysisResult;

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

        // 3. Invoke Leonardo (Architect)
        String backendEntityGuidance = generateBackendObjects ?
            "USER PREFERENCE: The user requested to ALSO generate Batch Client Extensions (Liferay Objects) for any backend entities used/referenced by this module (e.g. " + String.join(", ", analysis.detectedEntities) + "). Generate the Object Definition batch files and client-extension.yaml so that the Custom Element has an active backend API (/o/c/[slug])." :
            "USER PREFERENCE: The user chose NOT to generate backend Objects. Assume the Liferay Objects already exist or are migrated separately. DO NOT generate any Batch Client Extension. Focus strictly on generating the Frontend Custom Element Client Extension (calling /o/c/[slug]).";

        String leonardoSystemInstruction = "You are Leonardo, the Lead Architect for modernizing legacy Liferay 7.x OSGi modules into modern Liferay Objects and Client Extensions.\n" +
            "The source platform is: " + analysis.sourceLiferayVersion + ".\n" +
            "Rigorously follow the architectural mapping rules below:\n\n" + mappingPlaybook;

        String leonardoPrompt = "Analyze the following legacy OSGi module extract and produce a comprehensive modernization blueprint.\n" +
            backendEntityGuidance + "\n\n" +
            "Determine:\n" +
            "1. Liferay Object definitions (ignoring audit fields like companyId, userId, createDate), ONLY if backend objects are requested.\n" +
            "2. Required Batch Client Extension structure, ONLY if backend objects are requested.\n" +
            "3. Frontend UI components (React JavaScript Custom Elements or Page Fragments), if portlets exist.\n" +
            "4. Model Listeners / Service Wrappers mapping (Native Object Actions vs Object Action CETs).\n\n" +
            legacyContext.toString();

        String blueprint;
        try {
            blueprint = geminiClient.generateContent(leonardoSystemInstruction, leonardoPrompt);
        } catch (Exception e) {
            throw new RuntimeException("[Phase 1 - Leonardo Architect Blueprinting]: " + e.getMessage(), e);
        }
        plan.architectureBlueprint = blueprint;

        // 4. Invoke Michelangelo (Coder) via Parallel Workers (Strategy A)
        boolean needBackend = generateBackendObjects && (!analysis.detectedEntities.isEmpty() || analysis.hasServiceBuilder);
        boolean needFrontend = analysis.hasJspPortlet || !analysis.detectedPortlets.isEmpty();

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
                "   DO NOT generate separate batch files for validations or actions! All field constraints (required, unique) belong directly on objectFields inside 01-02, and notifications are configured via Liferay Control Panel per MIGRATION.md!\n" +
                "2. DO NOT generate package.json, React components, or frontend files in this call!\n" +
                "3. In 01-00-folder-definition: 'name' MUST be alphanumeric with NO SPACES (e.g. 'DevconMigrationObjects').\n" +
                "4. NEVER include reserved system field names ('status', 'id', 'companyId', 'groupId', 'userId', 'userName', 'createDate', 'modifiedDate', 'uuid') in objectFields!\n" +
                "5. NEVER include 'objectLayouts' or 'objectViews' inside ObjectDefinition items! Liferay creates layouts automatically.\n" +
                "6. NEVER add inline 'options' inside objectFieldSettings! For dropdowns/select fields, generate a Picklist in 01-01 and reference it via 'listTypeDefinitionExternalReferenceCode'!\n" +
                "7. In Picklist listTypeEntries: 'key' MUST be alphanumeric with NO spaces, underscores, or hyphens (e.g. 'inPerson', 'M').\n" +
                "8. In client-extension.yaml, include scopes: Liferay.Headless.Batch.Engine.everything, Liferay.Object.Admin.REST.everything, Liferay.Headless.Admin.List.Type.everything.\n";

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

        if (needFrontend) {
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
                "6. DO NOT import @clayui/* via npm! Use standard HTML elements with Liferay native Clay CSS classes (container, form-group, form-control, btn btn-primary, table table-autofit).\n" +
                "7. In entity lists/tables: EVERY row MUST provide working onClick handlers for Edit and Delete (never an unwired static Actions button)!\n" +
                "8. In src/services/liferayApi.js and src/App.js: Liferay Objects REST endpoints are ALWAYS lowercase alphanumeric with NO HYPHENS! (e.g. '/o/c/devconregistrations', NOT '/o/c/devcon-registrations').\n" +
                "9. In src/services/liferayApi.js: Always use normalizeUrl(path) and merge 'Content-Type': 'application/json' and 'Accept': 'application/json'.\n" +
                "10. If generating Page Fragments, put them in 'fragments/[collection-slug]/': collection.json, [fragment-slug]/fragment.json (with 'fragmentEntryKey'), index.html, index.css. Never in 'client-extensions/'!\n" +
                "11. In React form state and API payloads: NEVER include 'status' (e.g. status: 0)! In Liferay Objects, status is a system-managed workflow state. Sending 'status' in POST/PUT causes 'Unable to map JSON path: status' HTTP 400 error! In liferayApi.js, createObjectEntry and updateObjectEntry MUST defensively strip 'status', 'id', 'creator', 'dateCreated', and 'dateModified' before sending!\n" +
                "12. In React dropdowns (<select>) and radio options: Option values MUST MATCH Picklist entry keys EXACTLY! In Liferay, Picklist keys are alphanumeric (e.g. 'INPERSON', 'VIRTUAL', 'S', 'M'). NEVER use underscores like 'IN_PERSON' if the Picklist key is 'INPERSON', or Liferay will reject with 'not mapped to a valid list type entry'!\n";

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

        return plan;
    }

    private void parseGeneratedFiles(String rawOutput, Map<String, String> targetMap) {
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
