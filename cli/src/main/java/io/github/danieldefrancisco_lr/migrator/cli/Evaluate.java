package io.github.danieldefrancisco_lr.migrator.cli;

import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator;
import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator.MigrationPlan;
import io.github.danieldefrancisco_lr.migrator.core.agent.GeminiSettings;
import io.github.danieldefrancisco_lr.migrator.core.agent.LlmClient;
import io.github.danieldefrancisco_lr.migrator.core.agent.LlmClients;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.FileSystemModuleSource;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleAnalysisResult;
import io.github.danieldefrancisco_lr.migrator.core.output.MigrationReports;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * 'eval': runs the migration of one or more modules with the configured provider, without writing anything to the
 * workspace, and reports per run the time, the generated files and Raphael's warnings. Running it once per provider
 * (or model, or effort) on the same modules compares them on the measure that matters here: how many of the checks
 * validated on Liferay the generated files fail.
 */
final class Evaluate {

    static final class Run {
        String module;
        int run;
        long millis;
        int files;
        int warnings = -1;
        List<String> warningList = new ArrayList<>();
        String error;
    }

    private Evaluate() {
    }

    /**
     * Saves every answer of the provider next to the plan ([prefix]-response-N.txt, headed by the first line of the
     * agent's instruction), to see what a model wrote when the plan misses files. The agents of phase 2 run in
     * parallel, so N is the order in which the answers arrive.
     */
    static LlmClient recording(LlmClient client, Path outDir, String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return (system, prompt) -> {
            String answer = client.generateContent(system, prompt);
            String agent = system == null ? "" : system.strip().split("\n", 2)[0];
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve(prefix + "-response-" + counter.incrementAndGet() + ".txt"),
                "=== AGENT: " + agent + " ===\n" + answer, StandardCharsets.UTF_8);
            return answer;
        };
    }

    static int run(List<String> modules, int runs, boolean backend, Path outDir, boolean json, GeminiSettings settings,
                   LlmClient client, PrintStream out, PrintStream err, LongSupplier clock) throws Exception {
        String provider = LlmClients.describe(settings);
        List<Run> results = new ArrayList<>();
        for (String module : modules) {
            File moduleDir = new File(module).getAbsoluteFile();
            if (!moduleDir.isDirectory()) {
                err.println("Error: module directory not found: " + moduleDir);
                return 1;
            }
            ModuleAnalysisResult analysis = LegacyModuleAnalyzer.analyze(new FileSystemModuleSource(moduleDir, moduleDir));
            for (int i = 1; i <= runs; i++) {
                Run r = new Run();
                r.module = moduleDir.getName();
                r.run = i;
                err.println("Evaluating " + r.module + " (run " + i + "/" + runs + ") with " + provider + " ...");
                long start = clock.getAsLong();
                try {
                    LlmClient runClient = outDir == null ? client : recording(client, outDir, r.module + "-run" + i);
                    MigrationPlan plan = new AgentOrchestrator(runClient).planAndGenerate(analysis, moduleDir.getParent(), backend);
                    r.millis = clock.getAsLong() - start;
                    r.files = plan.generatedFiles.size();
                    MigrationReports.Checks checks = MigrationReports.runChecks(plan, true);
                    r.warnings = checks.warnings.size();
                    r.warningList.addAll(checks.warnings);
                    if (outDir != null) {
                        Files.createDirectories(outDir);
                        PlanFile.write(outDir.resolve(r.module + "-run" + i + ".plan.json"), plan);
                    }
                } catch (Exception e) {
                    r.millis = clock.getAsLong() - start;
                    r.error = e.getMessage();
                }
                results.add(r);
            }
        }

        if (json) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("provider", provider);
            List<Object> list = new ArrayList<>();
            for (Run r : results) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("module", r.module);
                m.put("run", r.run);
                m.put("seconds", r.millis / 1000.0);
                m.put("files", r.files);
                m.put("warnings", r.error == null ? r.warnings : null);
                m.put("warningList", r.warningList);
                m.put("error", r.error);
                list.add(m);
            }
            result.put("runs", list);
            out.println(Json.write(result));
        } else {
            out.println("Provider: " + provider + "\n");
            out.println(String.format("%-28s %4s %9s %6s %9s  %s", "Module", "Run", "Seconds", "Files", "Warnings", "Error"));
            int ok = 0, totalWarnings = 0, clean = 0;
            long totalMillis = 0;
            for (Run r : results) {
                out.println(String.format("%-28s %4d %9.1f %6d %9s  %s", r.module, r.run, r.millis / 1000.0, r.files,
                    r.error == null ? String.valueOf(r.warnings) : "-", r.error == null ? "" : r.error));
                if (r.error == null) {
                    ok++;
                    totalWarnings += r.warnings;
                    totalMillis += r.millis;
                    if (r.warnings == 0) {
                        clean++;
                    }
                }
            }
            out.println();
            out.println(String.format("Completed runs: %d/%d   clean (0 warnings): %d   average warnings: %s   average time: %s",
                ok, results.size(), clean, ok == 0 ? "-" : String.format("%.1f", (double) totalWarnings / ok),
                ok == 0 ? "-" : String.format("%.0f s", totalMillis / 1000.0 / ok)));
            for (Run r : results) {
                if (!r.warningList.isEmpty()) {
                    out.println("\n" + r.module + " run " + r.run + " warnings:");
                    for (String w : r.warningList) {
                        out.println("  - " + w);
                    }
                }
            }
        }
        return results.stream().allMatch(r -> r.error == null) ? 0 : 1;
    }
}
