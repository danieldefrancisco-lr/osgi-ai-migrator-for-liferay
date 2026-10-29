package io.github.danieldefrancisco_lr.migrator.agent;

import io.github.danieldefrancisco_lr.migrator.analyzer.LegacyModuleAnalyzer.ModuleAnalysisResult;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Coordinates Donatello, Leonardo, Michelangelo, and Raphael to execute the migration via Gemini.
 */
public class AgentOrchestrator {

    private final GeminiClient geminiClient;

    public AgentOrchestrator() {
        this.geminiClient = new GeminiClient();
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
        legacyContext.append("Schedulers Detected: ").append(String.join(", ", analysis.detectedSchedulers)).append("\n\n");

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

        // 4. Invoke Michelangelo (Coder)
        String michelangeloSystemInstruction = "You are Michelangelo, the Senior Coder agent specialized in Liferay Client Extensions and Batch Engine data definitions.\n" +
            "Follow the generation rules below:\n\n" + generationPlaybook + "\n\n" +
            "### CANONICAL BATCH OBJECT TEMPLATE:\n" + batchTemplate + "\n\n" +
            "### STRICT DIRECTORY & FILE NAMING RULES:\n" +
            "1. EVERY generated file MUST start with the client extension directory: 'client-extensions/<name>/...'\n" +
            "2. For Service Builder entities, generate a BATCH CLIENT EXTENSION:\n" +
            "   - client-extensions/[entity-slug]-object/client-extension.yaml\n" +
            "   - client-extensions/[entity-slug]-object/batch/01-00-folder-definition.batch-engine-data.json\n" +
            "   - client-extensions/[entity-slug]-object/batch/01-01-[entity-slug]-definition.batch-engine-data.json\n" +
            "   DO NOT generate package.json or src/index.js for Batch Client Extensions!\n" +
            "3. For JSP Portlets, generate a standard REACT JAVASCRIPT CUSTOM ELEMENT CLIENT EXTENSION:\n" +
            "   - ALWAYS use plain JavaScript (.js, .jsx). NEVER use TypeScript (.ts, .tsx)!\n" +
            "   - client-extensions/[portlet-slug]-custom-element/client-extension.yaml (assemble from 'build/static' into 'static', urls: ['js/main.*.js'], cssURLs: ['css/main.*.css'])\n" +
            "   - client-extensions/[portlet-slug]-custom-element/package.json (MUST use react-scripts: \"start\": \"react-scripts start\", \"build\": \"react-scripts build\")\n" +
            "   - client-extensions/[portlet-slug]-custom-element/public/index.html (HTML5 shell)\n" +
            "   - client-extensions/[portlet-slug]-custom-element/src/index.js (Web component wrapper)\n" +
            "   - client-extensions/[portlet-slug]-custom-element/src/App.js\n" +
            "   - client-extensions/[portlet-slug]-custom-element/src/index.css\n" +
            "   - client-extensions/[portlet-slug]-custom-element/src/services/liferayApi.js\n" +
            "4. FRAGMENTS ARE NEVER CLIENT EXTENSIONS:\n" +
            "   - Liferay does NOT have a client extension type for fragments!\n" +
            "   - If generating Page Fragments (e.g. for card views or replacing ADTs), place them in 'fragments/[collection-slug]/':\n" +
            "     * fragments/[collection-slug]/collection.json (with name and description)\n" +
            "     * fragments/[collection-slug]/[fragment-slug]/fragment.json (MUST ALWAYS include 'fragmentEntryKey': '[unique-slug]', 'name': '...', 'type': 'component', 'htmlPath': 'index.html', 'cssPath': 'index.css', 'jsPath': 'index.js')\n" +
            "     * fragments/[collection-slug]/[fragment-slug]/index.html\n" +
            "     * fragments/[collection-slug]/[fragment-slug]/index.css\n" +
            "   - NEVER put fragments inside 'client-extensions/' and NEVER create client-extension.yaml for fragments!\n" +
            "5. Canonical client-extension.yaml for Batch:\n" +
            "   assemble:\n" +
            "     - from: batch\n" +
            "       into: batch\n" +
            "   [slug]-batch:\n" +
            "     name: '[Entity Name] Object Initialization'\n" +
            "     type: batch\n" +
            "     oAuthApplicationHeadlessServer: [slug]-oauth\n" +
            "   [slug]-oauth:\n" +
            "     .serviceAddress: localhost:8080\n" +
            "     .serviceScheme: http\n" +
            "     name: '[Entity Name] OAuth Headless Server'\n" +
            "     scopes:\n" +
            "       - Liferay.Headless.Batch.Engine.everything\n" +
            "       - Liferay.Object.Admin.REST.everything\n" +
            "     type: oAuthApplicationHeadlessServer\n" +
            "6. React JavaScript package.json & Clay CSS rules:\n" +
            "   - package.json MUST use react-scripts:\n" +
            "     \"dependencies\": { \"react\": \"^18.2.0\", \"react-dom\": \"^18.2.0\", \"react-scripts\": \"5.0.1\" },\n" +
            "     \"scripts\": { \"start\": \"react-scripts start\", \"build\": \"react-scripts build\" }\n" +
            "   - DO NOT import @clayui/layout, @clayui/form, @clayui/date-picker, or @clayui/button via npm packages! They cause missing export build errors!\n" +
            "   - Style UI using standard HTML elements with Liferay's native Clay CSS / Bootstrap classes: <div className=\"container\">, <div className=\"form-group\">, <input className=\"form-control\" />, <button className=\"btn btn-primary\">, <table className=\"table table-autofit\">\n" +
            "   - In entity tables/lists: NEVER output an unwired static 'Actions' button! Every row MUST have working onClick handlers for Edit (e.g. onEdit(item)) and Delete (e.g. onDelete(item.id)).\n";

        String michelangeloPrompt = "Based on Leonardo's blueprint below, generate the complete, ready-to-deploy files for the client extension.\n" +
            "Output each file clearly delimited with '=== FILE: [relative/path] ===' and '=== END FILE ==='.\n\n" +
            "Blueprint:\n" + blueprint + "\n\n" +
            "Legacy Context:\n" + legacyContext.toString();

        String rawGeneratedOutput;
        try {
            rawGeneratedOutput = geminiClient.generateContent(michelangeloSystemInstruction, michelangeloPrompt);
        } catch (Exception e) {
            throw new RuntimeException("[Phase 2 - Michelangelo Code Generation]: " + e.getMessage(), e);
        }
        parseGeneratedFiles(rawGeneratedOutput, plan.generatedFiles);

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
