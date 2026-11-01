package io.github.danieldefrancisco_lr.migrator.tests;

import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator;
import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator.MigrationPlan;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleAnalysisResult;
import io.github.danieldefrancisco_lr.migrator.core.output.MigrationReports;
import io.github.danieldefrancisco_lr.migrator.ui.MigrationWizardDialog;
import io.github.danieldefrancisco_lr.migrator.workspace.EclipseModuleSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests: run the whole pipeline (analyzer, agents with a recorded Gemini, reports and
 * writing files to the workspace) on the fixtures in testdata/fixtures and compare with testdata/golden.
 */
public class CharacterizationTest {

    @Test
    public void libraryService() throws Exception {
        run("library-service", "gradle-workspace", "modules/library/library-service", null, true);
    }

    @Test
    public void libraryWeb() throws Exception {
        run("library-web", "gradle-workspace", "modules/library/library-web", null, true);
    }

    @Test
    public void libraryWebFrontendOnly() throws Exception {
        run("library-web-frontend-only", "gradle-workspace", "modules/library/library-web", null, false);
    }

    @Test
    public void libraryWebSelectedFolder() throws Exception {
        run("library-web-selected-folder", "gradle-workspace", "modules/library/library-web", "src/main/resources", true);
    }

    /** Gemini puts a fragment collection under client-extensions/; the writer re-routes it to fragments/. */
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

    @Test
    public void acmeTheme() throws Exception {
        run("acme-theme", "maven-workspace", "themes/acme-theme", null, false);
    }

    @Test
    public void legacyTasks() throws Exception {
        run("legacy-tasks", "standalone", "legacy-tasks", null, true);
    }

    private void run(String caseName, String fixture, String modulePath, String selectedFolder, boolean generateBackend) throws Exception {
        run(caseName, fixture, modulePath, selectedFolder, generateBackend, "default");
    }

    private void run(String caseName, String fixture, String modulePath, String selectedFolder, boolean generateBackend,
                     String responses) throws Exception {
        Path tmp = Files.createTempDirectory("oam-").toRealPath();
        Path fixtureCopy = tmp.resolve(fixture);
        copyTree(Golden.TESTDATA.resolve("fixtures").resolve(fixture), fixtureCopy);
        Path moduleDir = fixtureCopy.resolve(modulePath);
        Golden golden = new Golden(caseName, tmp);

        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        String projectName = moduleDir.getFileName().toString();
        IProject project = workspace.getRoot().getProject(projectName);
        IProjectDescription description = workspace.newProjectDescription(projectName);
        description.setLocation(new org.eclipse.core.runtime.Path(moduleDir.toString()));
        project.create(description, null);
        project.open(null);
        try {
            IResource resource = selectedFolder == null ? project : project.getFolder(selectedFolder);

            ModuleAnalysisResult analysis = LegacyModuleAnalyzer.analyze(new EclipseModuleSource(resource));
            golden.check("analysis.txt", Dumps.analysis(analysis));

            RecordingGemini gemini = new RecordingGemini(responses);
            AgentOrchestrator orchestrator = new AgentOrchestrator(gemini);
            MigrationPlan plan = orchestrator.planAndGenerate(analysis, workspace.getRoot().getLocation().toOSString(), generateBackend);

            golden.check("calls.txt", String.join("\n", gemini.calls.keySet()) + "\n");
            for (Map.Entry<String, String[]> call : gemini.calls.entrySet()) {
                golden.check("prompts/" + call.getKey() + "-system.txt", call.getValue()[0]);
                golden.check("prompts/" + call.getKey() + "-prompt.txt", call.getValue()[1]);
            }
            golden.check("plan.txt", Dumps.plan(plan));

            MigrationWizardDialog dialog = allocate(MigrationWizardDialog.class);
            setField(dialog, "targetResource", resource);
            setField(dialog, "analysisResult", analysis);
            setField(dialog, "currentPlan", plan);
            golden.check("donatello-report.txt", MigrationReports.buildDonatelloReport(analysis));
            golden.check("raphael-report.txt", MigrationReports.buildRaphaelVerificationReport(analysis, plan));
            golden.check("migration-report.md", MigrationReports.generateMigrationMarkdownReport(analysis, plan));

            Set<String> before = Dumps.listFiles(fixtureCopy);
            List<String> messages = applyClosingMessageDialogs(dialog);
            golden.check("apply-messages.txt", String.join("\n----\n", messages) + "\n");
            golden.check("output.txt", Dumps.newFiles(fixtureCopy, before));
        } finally {
            project.delete(false, true, null);
            deleteTree(tmp);
        }
    }

    /** Runs "Apply to Workspace", closing the resulting message dialogs and returning their title and text. */
    private static List<String> applyClosingMessageDialogs(MigrationWizardDialog dialog) throws Exception {
        Display display = Display.getDefault();
        List<String> messages = new ArrayList<>();
        boolean[] done = { false };
        Runnable closer = new Runnable() {
            @Override
            public void run() {
                for (Shell shell : display.getShells()) {
                    if (!shell.isDisposed()) {
                        StringBuilder sb = new StringBuilder(shell.getText());
                        collectLabels(shell, sb);
                        messages.add(sb.toString());
                        shell.close();
                    }
                }
                if (!done[0]) {
                    display.timerExec(50, this);
                }
            }
        };
        display.timerExec(50, closer);
        try {
            invoke(dialog, "applyGeneratedFilesToWorkspace");
        } finally {
            done[0] = true;
            // Cancel the pending tick so it cannot close (and record) the next test's dialogs
            display.timerExec(-1, closer);
        }
        return messages;
    }

    private static void collectLabels(Composite parent, StringBuilder sb) {
        for (Control child : parent.getChildren()) {
            if (child instanceof Label && !((Label) child).getText().isEmpty()) {
                sb.append("\n").append(((Label) child).getText());
            } else if (child instanceof Composite) {
                collectLabels((Composite) child, sb);
            }
        }
    }

    /**
     * Creates an instance without running its constructor. The dialog's constructor builds a java.net.http.HttpClient,
     * which needs loopback sockets that some sandboxes block; these tests never talk to Gemini.
     */
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> owner = target.getClass();
        while (!hasField(owner, name)) {
            owner = owner.getSuperclass();
        }
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static boolean hasField(Class<?> type, String name) {
        for (Field f : type.getDeclaredFields()) {
            if (f.getName().equals(name)) return true;
        }
        return false;
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                return method.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(name);
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
