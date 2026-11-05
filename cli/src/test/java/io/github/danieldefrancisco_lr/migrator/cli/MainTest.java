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
    public void settingsPrecedenceIsOptionsThenFileThenEnvironment() throws Exception {
        Path config = tmp.resolve("config.properties");
        Files.writeString(config, "geminiModel=from-file\nvertexProjectId=file-project\n");
        CliSettings settings = new CliSettings(
            Map.of(GeminiSettings.P_GEMINI_MODEL, "from-option"),
            Map.of("GEMINI_API_KEY", "env-key-5678", "GOOGLE_CLOUD_PROJECT", "env-project", "GOOGLE_CLOUD_LOCATION", "us-east1"),
            config);
        assertEquals("from-option", settings.get(GeminiSettings.P_GEMINI_MODEL));
        assertEquals("file-project", settings.get(GeminiSettings.P_VERTEX_PROJECT_ID), "the config file wins over shared env vars");
        assertEquals("us-east1", settings.get(GeminiSettings.P_VERTEX_REGION), "env vars fill what the file leaves unset");
        assertEquals("env-key-5678", settings.getSecret(GeminiSettings.P_GEMINI_API_KEY));
        assertEquals(GeminiSettings.AUTH_TYPE_GCLOUD_ADC, settings.get(GeminiSettings.P_AUTH_TYPE),
            "an API key in the environment alone must not switch to AI Studio");
        assertTrue(settings.describe().contains("****5678"), "the API key must be masked");
        assertTrue(!settings.describe().contains("env-key-5678"));

        Files.writeString(config, "geminiAuthType=AI_STUDIO\n");
        CliSettings aiStudio = new CliSettings(Map.of(), Map.of(), config);
        assertEquals(GeminiSettings.AUTH_TYPE_AI_STUDIO, aiStudio.get(GeminiSettings.P_AUTH_TYPE));
    }

    @Test
    public void verifyChecksTheFilesInTheWorkspaceUntilTheyAreClean() throws Exception {
        Path definition = workspace.resolve("client-extensions/ticket-object/batch/01-02-ticket-definition.batch-engine-data.json");
        Files.createDirectories(definition.getParent());
        String json = "{\"configuration\": {\"className\": \"com.liferay.object.admin.rest.dto.v1_0.ObjectDefinition\"},\n" +
            " \"items\": [{\"externalReferenceCode\": \"TICKET\", \"name\": \"Ticket\", \"objectFields\": [\n" +
            "  {\"businessType\": \"Text\", \"name\": \"title\"FIELD_ERC}]}]}";
        Files.writeString(definition, json.replace("FIELD_ERC", ""));
        // Build output is not checked
        Path built = workspace.resolve("client-extensions/ticket-object/build/batch/01-02-ticket-definition.batch-engine-data.json");
        Files.createDirectories(built.getParent());
        Files.writeString(built, "{\"items\": [{\"objectFields\": [{\"name\": \"status\"}]}]}");

        assertEquals(Main.EXIT_WARNINGS, run("verify", workspace.toString()), err.toString());
        assertTrue(stdout().contains("[WARN] client-extensions/ticket-object/batch/01-02-ticket-definition.batch-engine-data.json " +
            "has an object field without externalReferenceCode ('title')"), stdout());
        assertTrue(!stdout().contains("/build/"), stdout());

        assertEquals(0, run("verify", workspace.toString(), "--only", "other-object"), err.toString());

        Files.writeString(definition, json.replace("FIELD_ERC", ", \"externalReferenceCode\": \"TICKET_TITLE\""));
        assertEquals(0, run("verify", workspace.toString(), "--json"), err.toString());
        assertTrue(stdout().startsWith("{\"workspace\":") && stdout().contains("\"warnings\":[]"), stdout());
    }

    @Test
    public void appliesAThemeWarWithoutAPlanOrGemini() throws Exception {
        Path theme = Golden.TESTDATA.resolve("fixtures/maven-workspace/themes/acme-theme/src/main/webapp");
        Path war = tmp.resolve("acme-theme.war");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(war));
             Stream<Path> files = Files.walk(theme)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                zip.putNextEntry(new java.util.zip.ZipEntry(theme.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, zip);
                zip.closeEntry();
            }
        }

        assertEquals(2, run("apply", war.toString()), "a .war needs --workspace");
        assertEquals(0, run("apply", war.toString(), "--workspace", workspace.toString()), err.toString());

        Path cx = workspace.resolve("client-extensions/acme-theme");
        assertTrue(Files.readString(cx.resolve("client-extension.yaml")).contains("type: themeCSS"));
        assertTrue(Files.exists(cx.resolve("src/css/_custom.scss")));
        assertTrue(Files.exists(cx.resolve("src/css/components/_breadcrumb.scss")), "base partials the theme imports are copied");
        assertTrue(!Files.exists(cx.resolve("src/css/components/_unused.scss")), "base partials it does not import are not");
        assertTrue(!Files.exists(cx.resolve("src/css/clay")), "Clay comes from the design pack");
        assertEquals(Files.size(theme.resolve("images/logo.png")), Files.size(cx.resolve("assets/images/logo.png")));
        assertEquals(0, run("verify", workspace.toString(), "--only", "acme-theme"), stdout());
    }

    @Test
    public void knowledgeExportsThePlaybooksOfThisVersion() throws Exception {
        Path dir = tmp.resolve("knowledge");
        assertEquals(0, run("knowledge", dir.toString()), err.toString());
        String playbook = Files.readString(dir.resolve("playbooks/03-generation-rules.md"), StandardCharsets.UTF_8);
        assertTrue(playbook.startsWith("# Michelangelo (The Coder) Playbook"), playbook.substring(0, 80));
        assertTrue(Files.exists(dir.resolve("blueprints/notification-and-workflow-migration.md")));
    }

    @Test
    public void providerOptionsGoToTheSelectedProvider() throws Exception {
        Path config = tmp.resolve("config.properties");
        Files.writeString(config, "llmProvider=anthropic\nanthropicEffort=medium\n");
        CliSettings claude = new CliSettings(Map.of(CliSettings.OPTION_MODEL, "claude-sonnet-5-5"),
            Map.of("ANTHROPIC_API_KEY", "sk-ant-secret-9999", "OPENAI_API_KEY", "sk-openai-1111"), config);
        assertEquals(GeminiSettings.PROVIDER_ANTHROPIC, claude.get(GeminiSettings.P_PROVIDER));
        assertEquals("claude-sonnet-5-5", claude.get(GeminiSettings.P_ANTHROPIC_MODEL), "--model applies to the provider in effect");
        assertEquals(null, claude.get(GeminiSettings.P_GEMINI_MODEL));
        assertEquals("medium", claude.get(GeminiSettings.P_ANTHROPIC_EFFORT));
        assertEquals("sk-ant-secret-9999", claude.getSecret(GeminiSettings.P_ANTHROPIC_API_KEY));
        String described = claude.describe();
        assertTrue(described.contains("****9999") && described.contains("****1111"), described);
        assertTrue(!described.contains("sk-ant-secret") && !described.contains("sk-openai"), "every API key must be masked");
        assertTrue(!described.contains(GeminiSettings.P_AUTH_TYPE), "no Gemini default when another provider is selected");

        CliSettings local = new CliSettings(Map.of(GeminiSettings.P_PROVIDER, "openai", CliSettings.OPTION_MODEL, "qwen3-coder",
            CliSettings.OPTION_ENDPOINT, "http://localhost:11434/v1"), Map.of(), config);
        assertEquals(GeminiSettings.PROVIDER_OPENAI, local.get(GeminiSettings.P_PROVIDER), "the command line wins over the file");
        assertEquals("qwen3-coder", local.get(GeminiSettings.P_OPENAI_MODEL));
        assertEquals("http://localhost:11434/v1", local.get(GeminiSettings.P_OPENAI_BASE_URL));

        assertEquals(1, run("migrate", module.toString(), "--provider", "copilot"));
        assertTrue(err.toString().contains("Unknown AI provider 'COPILOT'"), err.toString());
    }

    @Test
    public void evalReportsWarningsPerRunWithoutWriting() throws Exception {
        Set<String> before = Dumps.listFiles(workspace);
        assertEquals(0, run("eval", module.toString(), "--runs", "2", "--json"), err.toString());
        assertEquals(before, Dumps.listFiles(workspace), "eval must not write into the workspace");
        String json = stdout();
        assertTrue(json.startsWith("{\"provider\":\"Gemini gemini-2.5-flash (GCLOUD_ADC)\",\"runs\":[{\"module\":\"library-web\",\"run\":1,"), json);
        assertTrue(json.contains("\"run\":2,"), json);
        assertTrue(json.contains("\"warnings\":"), json);

        Path outDir = tmp.resolve("eval-out");
        assertEquals(0, run("eval", module.toString(), "--out", outDir.toString()), err.toString());
        assertTrue(Files.isRegularFile(outDir.resolve("library-web-run1.plan.json")));
        String first = Files.readString(outDir.resolve("library-web-run1-response-1.txt"), StandardCharsets.UTF_8);
        assertTrue(first.startsWith("=== AGENT: "), first);
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
