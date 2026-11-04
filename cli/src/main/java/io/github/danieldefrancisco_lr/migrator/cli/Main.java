package io.github.danieldefrancisco_lr.migrator.cli;

import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator;
import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator.MigrationPlan;
import io.github.danieldefrancisco_lr.migrator.core.agent.GeminiClient;
import io.github.danieldefrancisco_lr.migrator.core.agent.GeminiSettings;
import io.github.danieldefrancisco_lr.migrator.core.agent.LlmClient;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.FileSystemModuleSource;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleAnalysisResult;
import io.github.danieldefrancisco_lr.migrator.core.output.ArtifactWriter;
import io.github.danieldefrancisco_lr.migrator.core.output.MigrationReports;
import io.github.danieldefrancisco_lr.migrator.core.output.WorkspaceFiles;
import io.github.danieldefrancisco_lr.migrator.core.output.ThemeGenerator;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Command-line interface: runs the same 4-agent pipeline as the IDE plugins on a module directory.
 * Results go to stdout (text, or JSON with --json); progress and errors go to stderr.
 */
public class Main {

    static final String USAGE =
        "Usage: osgi-ai-migrator <command> [options]\n" +
        "\n" +
        "Commands:\n" +
        "  analyze <module-dir>               Analyze a legacy module (Donatello) and print the report\n" +
        "  migrate <module-dir>               Run the 4-agent pipeline with Gemini and print the plan and checks\n" +
        "  apply <module-dir> --plan <file>   Write a plan saved with 'migrate --save-plan' into the Liferay workspace\n" +
        "                                     (a theme, as a folder or a .war, needs no plan: its client extension is built without AI)\n" +
        "  config                             Show the Gemini settings in effect and where they come from\n" +
        "  verify <workspace-dir>             Run Raphael's checks on the client extensions and fragments of a Liferay workspace\n" +
        "                                     (exit code 3 when there are warnings); --only <cx,cx> reports only those client extensions\n" +
        "  knowledge <dir>                    Export the playbooks, blueprints and schemas of this version\n" +
        "\n" +
        "Options:\n" +
        "  --folder <path>          Only analyze this folder of the module (relative to the module directory)\n" +
        "  --no-backend             Do not generate Liferay Objects (Batch Client Extension); frontend only\n" +
        "  --apply                  migrate: write the generated files into the Liferay workspace\n" +
        "  --save-plan <file>       migrate: save the plan to review it and write it later with 'apply'\n" +
        "  --workspace <dir>        Liferay workspace to write to (default: detected from the module)\n" +
        "  --json                   Machine-readable output\n" +
        "  --config <file>          Settings file (default: ~/.osgi-ai-migrator/config.properties)\n" +
        "\n" +
        "Gemini settings (precedence: these options, then the config file, then the environment):\n" +
        "  --auth <GCLOUD_ADC|VERTEX_AI|AI_STUDIO>   (default GCLOUD_ADC, as in Eclipse)\n" +
        "  --model <name>   --temperature <n>   --project <gcp-project>   --region <region>\n" +
        "  --service-account <file.json>   --endpoint <url>\n" +
        "  The API key is read from the config file (geminiApiKey) or GEMINI_API_KEY, never from the command line.\n" +
        "\n" +
        "  -h, --help   --version\n";

    private static final Map<String, String> SETTING_OPTIONS = Map.of(
        "--auth", GeminiSettings.P_AUTH_TYPE,
        "--model", GeminiSettings.P_GEMINI_MODEL,
        "--temperature", GeminiSettings.P_TEMPERATURE,
        "--project", GeminiSettings.P_VERTEX_PROJECT_ID,
        "--region", GeminiSettings.P_VERTEX_REGION,
        "--service-account", GeminiSettings.P_SERVICE_ACCOUNT_FILE,
        "--endpoint", GeminiSettings.P_GEMINI_ENDPOINT);

    private static final List<String> VALUE_OPTIONS = List.of("--folder", "--save-plan", "--workspace", "--config", "--plan", "--only");

    /** Exit code of 'verify' when Raphael has warnings, so agents and scripts can loop until it is clean. */
    static final int EXIT_WARNINGS = 3;
    private static final List<String> FLAGS = List.of("--no-backend", "--apply", "--json");

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, System.getenv(), GeminiClient::new));
    }

    static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> env,
                   Function<GeminiSettings, LlmClient> llmFactory) {
        List<String> positional = new ArrayList<>();
        Map<String, String> options = new HashMap<>();
        Map<String, String> settingOptions = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-h") || arg.equals("--help")) {
                out.print(USAGE);
                return 0;
            } else if (arg.equals("--version")) {
                out.println("osgi-ai-migrator " + version());
                return 0;
            } else if (FLAGS.contains(arg)) {
                options.put(arg, "true");
            } else if (VALUE_OPTIONS.contains(arg) || SETTING_OPTIONS.containsKey(arg)) {
                if (i + 1 >= args.length) {
                    return usageError(err, "Missing value for " + arg);
                }
                String value = args[++i];
                if (SETTING_OPTIONS.containsKey(arg)) {
                    settingOptions.put(SETTING_OPTIONS.get(arg), value);
                } else {
                    options.put(arg, value);
                }
            } else if (arg.startsWith("-")) {
                return usageError(err, "Unknown option " + arg);
            } else {
                positional.add(arg);
            }
        }
        if (positional.isEmpty()) {
            return usageError(err, "Missing command");
        }

        String command = positional.get(0);
        boolean json = options.containsKey("--json");
        try {
            Path configFile = options.containsKey("--config") ? Path.of(options.get("--config")) : CliSettings.DEFAULT_CONFIG_FILE;
            CliSettings settings = new CliSettings(settingOptions, env, configFile);
            if (command.equals("config")) {
                out.print(settings.describe());
                return 0;
            }
            if (command.equals("verify") || command.equals("knowledge")) {
                if (positional.size() < 2) {
                    return usageError(err, "Missing " + (command.equals("verify") ? "<workspace-dir>" : "<dir>"));
                }
                Path dir = Path.of(positional.get(1)).toAbsolutePath();
                return command.equals("verify") ? verify(dir, options.get("--only"), json, out, err) : knowledge(dir, json, out);
            }
            if (!List.of("analyze", "migrate", "apply").contains(command)) {
                return usageError(err, "Unknown command " + command);
            }
            if (positional.size() < 2) {
                return usageError(err, "Missing <module-dir>");
            }

            File moduleDir = new File(positional.get(1)).getAbsoluteFile();
            if (moduleDir.isFile() && moduleDir.getName().endsWith(".war")) {
                // A deployed theme WAR: analyze an extracted copy (the theme's files are copied from it on apply)
                if (!command.equals("analyze") && !options.containsKey("--workspace")) {
                    return usageError(err, "A .war is not inside a Liferay workspace: pass --workspace <dir>");
                }
                File extracted = Path.of(System.getProperty("user.home"), ".osgi-ai-migrator", "extracted",
                    moduleDir.getName().substring(0, moduleDir.getName().length() - 4)).toFile();
                WarFiles.extract(moduleDir.toPath(), extracted.toPath());
                err.println("Extracted " + moduleDir.getName() + " to " + extracted);
                moduleDir = extracted;
            }
            if (!moduleDir.isDirectory()) {
                err.println("Error: module directory not found: " + moduleDir);
                return 1;
            }
            File selected = options.containsKey("--folder") ? new File(moduleDir, options.get("--folder")) : moduleDir;
            if (!selected.isDirectory()) {
                err.println("Error: folder not found: " + selected);
                return 1;
            }

            err.println("Donatello: analyzing " + selected + " ...");
            ModuleAnalysisResult analysis = LegacyModuleAnalyzer.analyze(new FileSystemModuleSource(moduleDir, selected));
            String analysisReport = MigrationReports.buildDonatelloReport(analysis);
            if (command.equals("analyze")) {
                if (json) {
                    out.println(Json.write(analysisJson(analysis, analysisReport)));
                } else {
                    out.print(analysisReport);
                }
                return 0;
            }

            if (command.equals("apply") && !options.containsKey("--plan") && analysis.theme == null) {
                return usageError(err, "apply needs --plan <file> (only a theme can be applied without one)");
            }
            if (command.equals("apply") && options.containsKey("--save-plan")) {
                return usageError(err, "--save-plan is for migrate");
            }

            MigrationPlan plan;
            if (command.equals("apply")) {
                plan = options.containsKey("--plan") ? PlanFile.read(Path.of(options.get("--plan"))) : new MigrationPlan();
                if (analysis.theme != null) {
                    // The theme client extension needs no AI: build it from the theme's files on this machine
                    String themeDir = "client-extensions/" + ThemeGenerator.slug(analysis.theme.themeId) + "/";
                    plan.generatedFiles.keySet().removeIf(p -> p.startsWith(themeDir));
                    plan.copiedFiles.keySet().removeIf(p -> p.startsWith(themeDir));
                    ThemeGenerator.generate(analysis.theme, plan);
                }
            } else {
                err.println("Leonardo and Michelangelo: generating with Gemini (" + settings.get(GeminiSettings.P_AUTH_TYPE) +
                    "), this can take a minute ...");
                AgentOrchestrator orchestrator = new AgentOrchestrator(llmFactory.apply(settings));
                plan = orchestrator.planAndGenerate(analysis, moduleDir.getParent(), !options.containsKey("--no-backend"));
                if (options.containsKey("--save-plan")) {
                    PlanFile.write(Path.of(options.get("--save-plan")), plan);
                    err.println("Plan saved to " + options.get("--save-plan"));
                }
            }
            String verificationReport = MigrationReports.buildRaphaelVerificationReport(analysis, plan);

            ArtifactWriter.WriteResult written = null;
            File workspace = options.containsKey("--workspace") ? new File(options.get("--workspace")).getAbsoluteFile()
                : new File(analysis.liferayWorkspaceRoot);
            if (command.equals("apply") || options.containsKey("--apply")) {
                err.println("Writing files into " + workspace + " ...");
                written = ArtifactWriter.writeArtifacts(analysis, plan, workspace);
            }

            if (json) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("analysis", analysisJson(analysis, analysisReport));
                result.put("blueprint", plan.architectureBlueprint);
                List<Object> files = new ArrayList<>();
                for (Map.Entry<String, String> e : plan.generatedFiles.entrySet()) {
                    files.add(Map.of("path", e.getKey(), "content", e.getValue()));
                }
                result.put("files", files);
                result.put("verificationReport", verificationReport);
                result.put("written", written == null ? null : writtenJson(written, workspace));
                out.println(Json.write(result));
            } else {
                if (command.equals("migrate")) {
                    out.println("=== LEONARDO (THE ARCHITECT) - BLUEPRINT ===\n");
                    out.println(plan.architectureBlueprint);
                    out.println("\n=== MICHELANGELO (THE CODER) - GENERATED FILES ===\n");
                    for (Map.Entry<String, String> e : plan.generatedFiles.entrySet()) {
                        out.println("  " + e.getKey() + " (" + e.getValue().length() + " chars)");
                    }
                    out.println();
                }
                out.print(verificationReport);
                if (written != null) {
                    out.println("\nWritten to " + workspace + ":");
                    out.println("  -> docs/migrations/" + written.moduleSlug + "-migration-report.md");
                    for (String dir : written.createdCeDirs) {
                        out.println("  -> client-extensions/" + dir + "/");
                    }
                    for (String col : written.fragmentCollections) {
                        out.println("  -> fragments/" + col + ".zip");
                    }
                } else if (command.equals("migrate")) {
                    out.println("\nNothing written. Review the plan, then run again with --apply, or save it with --save-plan " +
                        "and write it with 'apply'.");
                }
            }
            return 0;
        } catch (Exception e) {
            err.println("Error: " + e.getMessage());
            if (e.getCause() != null) {
                err.println("Caused by: " + e.getCause().getClass().getSimpleName() + " - " + e.getCause().getMessage());
            }
            return 1;
        }
    }

    /** Raphael on files already in the workspace; the checks see every client extension, the report can be narrowed with --only. */
    private static int verify(Path workspace, String only, boolean json, PrintStream out, PrintStream err) throws Exception {
        if (!Files.isDirectory(workspace)) {
            err.println("Error: workspace directory not found: " + workspace);
            return 1;
        }
        MigrationPlan files = WorkspaceFiles.read(workspace);
        if (files.generatedFiles.isEmpty()) {
            err.println("Error: no client-extensions/ or fragments/ files found in " + workspace);
            return 1;
        }
        MigrationReports.Checks checks = MigrationReports.runChecks(files, false);
        List<String> warnings = new ArrayList<>(checks.warnings);
        if (only != null) {
            List<String> prefixes = new ArrayList<>();
            for (String name : only.split(",")) {
                prefixes.add("client-extensions/" + name.trim() + "/");
            }
            warnings.removeIf(w -> prefixes.stream().noneMatch(w::contains));
        }
        if (json) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("workspace", workspace.toString());
            result.put("files", files.generatedFiles.size());
            result.put("warnings", warnings);
            out.println(Json.write(result));
        } else {
            out.println("Raphael checked " + files.generatedFiles.size() + " files in " + workspace +
                (only == null ? "" : " (reporting " + only + ")") + ":");
            for (String w : warnings) {
                out.println("  [WARN] " + w);
            }
            if (warnings.isEmpty()) {
                out.println("  [PASS] No problems found.");
            }
        }
        return warnings.isEmpty() ? 0 : EXIT_WARNINGS;
    }

    private static int knowledge(Path dir, boolean json, PrintStream out) throws Exception {
        List<String> written = WorkspaceFiles.exportKnowledge(dir);
        if (json) {
            out.println(Json.write(Map.of("dir", dir.toString(), "files", written)));
        } else {
            out.println("Knowledge base " + version() + " written to " + dir + ":");
            for (String f : written) {
                out.println("  " + f);
            }
        }
        return 0;
    }

    private static Map<String, Object> analysisJson(ModuleAnalysisResult a, String report) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("moduleName", a.moduleName);
        m.put("liferayWorkspaceRoot", a.liferayWorkspaceRoot);
        m.put("sourceLiferayVersion", a.sourceLiferayVersion);
        m.put("hasServiceBuilder", a.hasServiceBuilder);
        m.put("hasJspPortlet", a.hasJspPortlet);
        m.put("hasTheme", a.hasTheme);
        m.put("entities", a.detectedEntities);
        m.put("portlets", a.detectedPortlets);
        m.put("listeners", a.detectedListeners);
        m.put("schedulers", a.detectedSchedulers);
        m.put("notifications", a.detectedNotifications);
        m.put("customLogic", a.detectedCustomLogic);
        m.put("theme", a.theme == null ? null : a.theme.summary());
        List<String> renames = new ArrayList<>();
        for (String line : AgentOrchestrator.reservedColumnRenames(a).split("\n")) {
            if (!line.isBlank()) {
                renames.add(line.substring(2));
            }
        }
        m.put("fieldRenames", renames);
        m.put("report", report);
        return m;
    }

    private static Map<String, Object> writtenJson(ArtifactWriter.WriteResult w, File workspace) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workspace", workspace.getPath());
        m.put("report", "docs/migrations/" + w.moduleSlug + "-migration-report.md");
        m.put("clientExtensions", w.createdCeDirs);
        m.put("fragmentCollections", w.fragmentCollections);
        return m;
    }

    private static int usageError(PrintStream err, String message) {
        err.println("Error: " + message + "\n");
        err.print(USAGE);
        return 2;
    }

    private static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        // Releases are built from the -SNAPSHOT poms (Tycho adds a qualifier instead)
        return v != null ? v.replace("-SNAPSHOT", "") : "dev";
    }
}
