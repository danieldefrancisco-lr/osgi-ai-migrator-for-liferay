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

import java.io.File;
import java.io.PrintStream;
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
        "  config                             Show the Gemini settings in effect and where they come from\n" +
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
        "Gemini settings (override the environment and the config file):\n" +
        "  --auth <GCLOUD_ADC|VERTEX_AI|AI_STUDIO>   --model <name>   --temperature <n>\n" +
        "  --project <gcp-project>   --region <region>   --service-account <file.json>   --endpoint <url>\n" +
        "  The API key is read from GEMINI_API_KEY or the config file (key geminiApiKey), never from the command line.\n" +
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

    private static final List<String> VALUE_OPTIONS = List.of("--folder", "--save-plan", "--workspace", "--config", "--plan");
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
            if (!List.of("analyze", "migrate", "apply").contains(command)) {
                return usageError(err, "Unknown command " + command);
            }
            if (positional.size() < 2) {
                return usageError(err, "Missing <module-dir>");
            }

            File moduleDir = new File(positional.get(1)).getAbsoluteFile();
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

            if (command.equals("apply") && !options.containsKey("--plan")) {
                return usageError(err, "apply needs --plan <file>");
            }
            if (command.equals("apply") && options.containsKey("--save-plan")) {
                return usageError(err, "--save-plan is for migrate");
            }

            MigrationPlan plan;
            if (command.equals("apply")) {
                plan = PlanFile.read(Path.of(options.get("--plan")));
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
        return v != null ? v : "dev";
    }
}
