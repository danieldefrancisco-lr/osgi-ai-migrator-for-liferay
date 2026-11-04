package io.github.danieldefrancisco_lr.migrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator;
import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator.MigrationPlan;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.FileSystemModuleSource;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleAnalysisResult;
import io.github.danieldefrancisco_lr.migrator.core.output.ArtifactWriter;
import io.github.danieldefrancisco_lr.migrator.core.output.MigrationReports;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Runs the pipeline outside Eclipse (as the CLI and other IDEs will) on the same fixtures and checks it
 * produces the same goldens as the Eclipse plugin.
 */
public class CoreCharacterizationTest {

    @Test
    public void libraryService() throws Exception {
        run("library-service", "gradle-workspace", "modules/library/library-service", null, true, "default");
    }

    @Test
    public void libraryWeb() throws Exception {
        run("library-web", "gradle-workspace", "modules/library/library-web", null, true, "default");
    }

    @Test
    public void libraryWebFrontendOnly() throws Exception {
        run("library-web-frontend-only", "gradle-workspace", "modules/library/library-web", null, false, "default");
    }

    @Test
    public void libraryWebSelectedFolder() throws Exception {
        run("library-web-selected-folder", "gradle-workspace", "modules/library/library-web", "src/main/resources", true, "default");
    }

    @Test
    public void libraryWebMisplacedFragment() throws Exception {
        run("library-web-misplaced-fragment", "gradle-workspace", "modules/library/library-web", null, true, "misplaced-fragment");
    }

    /** Gemini answers with a Kubernetes-style client-extension.yaml, which Raphael must flag. */
    @Test
    public void libraryWebKubernetesYaml() throws Exception {
        run("library-web-kubernetes-yaml", "gradle-workspace", "modules/library/library-web", null, true, "kubernetes-yaml");
    }

    /** The React code calls the API with externalReferenceCodes and a URL without '/o/', which Raphael must flag. */
    @Test
    public void libraryWebErcAsPath() throws Exception {
        run("library-web-erc-as-path", "gradle-workspace", "modules/library/library-web", null, true, "erc-as-path");
    }

    /** The React code calls an endpoint Liferay Objects do not have (/count), which Raphael must flag. */
    @Test
    public void libraryWebInventedEndpoint() throws Exception {
        run("library-web-invented-endpoint", "gradle-workspace", "modules/library/library-web", null, true, "invented-endpoint");
    }

    @Test
    public void acmeTheme() throws Exception {
        run("acme-theme", "maven-workspace", "themes/acme-theme", null, false, "acme-theme");
    }

    @Test
    public void legacyTasks() throws Exception {
        run("legacy-tasks", "standalone", "legacy-tasks", null, true, "default");
    }

    /** Custom logic in a ModelListener and a LocalServiceImpl, ported to native actions and a Spring Boot microservice. */
    @Test
    public void loanService() throws Exception {
        run("loan-service", "gradle-workspace", "modules/loans/loan-service", null, true, "loan-service");
    }

    private void run(String caseName, String fixture, String modulePath, String selectedFolder, boolean generateBackend,
                     String responses) throws Exception {
        Path tmp = Files.createTempDirectory("oam-").toRealPath();
        Path fixtureCopy = tmp.resolve(fixture);
        copyTree(Golden.TESTDATA.resolve("fixtures").resolve(fixture), fixtureCopy);
        File moduleDir = fixtureCopy.resolve(modulePath).toFile();
        Golden golden = new Golden(caseName, tmp);
        try {
            FileSystemModuleSource source = selectedFolder == null
                ? new FileSystemModuleSource(moduleDir)
                : new FileSystemModuleSource(moduleDir, new File(moduleDir, selectedFolder));

            ModuleAnalysisResult analysis = LegacyModuleAnalyzer.analyze(source);
            golden.check("analysis.txt", Dumps.analysis(analysis));

            RecordingGemini gemini = new RecordingGemini(responses);
            MigrationPlan plan = new AgentOrchestrator(gemini).planAndGenerate(analysis, tmp.toString(), generateBackend);

            golden.check("calls.txt", String.join("\n", gemini.calls.keySet()) + "\n");
            for (Map.Entry<String, String[]> call : gemini.calls.entrySet()) {
                golden.check("prompts/" + call.getKey() + "-system.txt", call.getValue()[0]);
                golden.check("prompts/" + call.getKey() + "-prompt.txt", call.getValue()[1]);
            }
            golden.check("plan.txt", Dumps.plan(plan));
            golden.check("donatello-report.txt", MigrationReports.buildDonatelloReport(analysis));
            golden.check("raphael-report.txt", MigrationReports.buildRaphaelVerificationReport(analysis, plan));
            golden.check("migration-report.md", MigrationReports.generateMigrationMarkdownReport(analysis, plan));

            // The Eclipse dialog shows "Write Error" when writing fails; the files written up to then must match too
            Set<String> before = Dumps.listFiles(fixtureCopy);
            String outcome = "Success";
            try {
                ArtifactWriter.writeArtifacts(analysis, plan, new File(analysis.liferayWorkspaceRoot));
            } catch (Exception e) {
                outcome = "Write Error\nFailed to write files: " + e.getMessage();
            }
            // apply-messages.txt is recorded by the Eclipse tests, which run after these: when updating goldens it may
            // still hold the old outcome, and a new case has none yet
            Path eclipseMessagesFile = Golden.TESTDATA.resolve("golden/" + caseName + "/apply-messages.txt");
            if (Files.exists(eclipseMessagesFile) && !Boolean.getBoolean("golden.update")) {
                String eclipseMessages = Files.readString(eclipseMessagesFile, StandardCharsets.UTF_8);
                String expectedOutcome = eclipseMessages.startsWith("Write Error") ? eclipseMessages.trim() : "Success";
                assertEquals(expectedOutcome, golden.normalize(outcome).trim());
            }
            golden.check("output.txt", Dumps.newFiles(fixtureCopy, before));
        } finally {
            deleteTree(tmp);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                Path dest = target.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
