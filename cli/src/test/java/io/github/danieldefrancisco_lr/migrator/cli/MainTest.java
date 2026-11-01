package io.github.danieldefrancisco_lr.migrator.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.danieldefrancisco_lr.migrator.core.Dumps;
import io.github.danieldefrancisco_lr.migrator.core.Golden;
import io.github.danieldefrancisco_lr.migrator.core.RecordingGemini;
import io.github.danieldefrancisco_lr.migrator.core.agent.GeminiSettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs the CLI on the shared fixtures with a recorded Gemini; the files it writes must match the goldens
 * that the Eclipse plugin produces.
 */
public class MainTest {

    private Path tmp;
    private Path workspace;
    private Path module;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    public void copyFixture() throws IOException {
        tmp = Files.createTempDirectory("oam-cli-").toRealPath();
        workspace = tmp.resolve("gradle-workspace");
        copyTree(Golden.TESTDATA.resolve("fixtures/gradle-workspace"), workspace);
        module = workspace.resolve("modules/library/library-web");
    }

    @AfterEach
    public void deleteFixture() throws IOException {
        try (Stream<Path> paths = Files.walk(tmp)) {
            for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    public void migrateWithApplyWritesTheSameFilesAsThePlugin() throws Exception {
        Set<String> before = Dumps.listFiles(workspace);
        assertEquals(0, run("migrate", module.toString(), "--apply"), err.toString());
        new Golden("library-web", tmp).check("output.txt", Dumps.newFiles(workspace, before));
        assertTrue(stdout().contains("RAPHAEL (THE VERIFIER)"));
    }

    @Test
    public void savedPlanAppliedLaterWritesTheSameFiles() throws Exception {
        Path plan = tmp.resolve("plan.txt");
        Set<String> before = Dumps.listFiles(workspace);
        assertEquals(0, run("migrate", module.toString(), "--save-plan", plan.toString()), err.toString());
        assertEquals(before, Dumps.listFiles(workspace), "migrate without --apply must not write");
        assertTrue(stdout().contains("Nothing written"));

        assertEquals(0, run("apply", module.toString(), "--plan", plan.toString()), err.toString());
        new Golden("library-web", tmp).check("output.txt", Dumps.newFiles(workspace, before));
    }

    @Test
    public void frontendOnlySkipsTheBackendWorker() throws Exception {
        assertEquals(0, run("migrate", module.toString(), "--no-backend", "--json"), err.toString());
        String json = stdout();
        assertTrue(json.startsWith("{\"analysis\":{\"moduleName\":\"library-web\""), json);
        assertTrue(json.contains("client-extensions/library-web-custom-element/package.json"));
        assertTrue(!json.contains("\"path\":\"client-extensions/book-object/"), "backend files must not be generated");
    }

    @Test
    public void analyzePrintsTheDonatelloReport() throws Exception {
        assertEquals(0, run("analyze", module.toString(), "--folder", "src/main/resources"), err.toString());
        String expected = Files.readString(Golden.TESTDATA.resolve("golden/library-web-selected-folder/donatello-report.txt"), StandardCharsets.UTF_8);
        assertEquals(expected, new Golden("library-web-selected-folder", tmp).normalize(stdout()));
    }

    @Test
    public void usageErrorsExitWith2() {
        assertEquals(2, run());
        assertEquals(2, run("explode", module.toString()));
        assertEquals(2, run("apply", module.toString()));
        assertEquals(2, run("migrate", module.toString(), "--bogus"));
        assertEquals(0, run("--help"));
    }

    @Test
    public void settingsPrecedenceIsOptionsThenEnvironmentThenFile() throws Exception {
        Path config = tmp.resolve("config.properties");
        Files.writeString(config, "geminiModel=from-file\nvertexRegion=europe-west1\ngeminiApiKey=file-key-1234\n");
        CliSettings settings = new CliSettings(
            Map.of(GeminiSettings.P_GEMINI_MODEL, "from-option"),
            Map.of("GEMINI_API_KEY", "env-key-5678", "GOOGLE_CLOUD_LOCATION", "us-east1"),
            config);
        assertEquals("from-option", settings.get(GeminiSettings.P_GEMINI_MODEL));
        assertEquals("us-east1", settings.get(GeminiSettings.P_VERTEX_REGION));
        assertEquals("env-key-5678", settings.getSecret(GeminiSettings.P_GEMINI_API_KEY));
        assertEquals(GeminiSettings.AUTH_TYPE_AI_STUDIO, settings.get(GeminiSettings.P_AUTH_TYPE), "an API key selects AI Studio");
        assertTrue(settings.describe().contains("****5678"), "the API key must be masked");
        assertTrue(!settings.describe().contains("env-key-5678"));

        CliSettings defaults = new CliSettings(Map.of(), Map.of(), tmp.resolve("missing.properties"));
        assertEquals(GeminiSettings.AUTH_TYPE_GCLOUD_ADC, defaults.get(GeminiSettings.P_AUTH_TYPE));
    }

    private int run(String... args) {
        out.reset();
        err.reset();
        String[] withConfig = Stream.concat(Stream.of(args), Stream.of("--config", tmp.resolve("no-config.properties").toString()))
            .toArray(String[]::new);
        return Main.run(args.length == 0 ? args : withConfig, new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8), Map.of(), settings -> new RecordingGemini("default"));
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
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
}
