package io.github.danieldefrancisco_lr.migrator.core.output;

import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator.MigrationPlan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reads the client extensions and fragments already written in a Liferay workspace, so Raphael can check files that
 * were written or edited outside the migrator (e.g. by a coding agent), and exports the knowledge base.
 */
public class WorkspaceFiles {

    /** Workspace folders Raphael checks. */
    public static final List<String> ROOTS = List.of("client-extensions", "fragments");

    /** Every file of the knowledge base bundled in the core jar, relative to /knowledge/. */
    public static final List<String> KNOWLEDGE = List.of(
        "playbooks/01-analyzer-rules.md",
        "playbooks/02-mapping-rules.md",
        "playbooks/03-generation-rules.md",
        "playbooks/04-verifier-rules.md",
        "blueprints/data-migration-strategy.md",
        "blueprints/notification-and-workflow-migration.md",
        "blueprints/theme-and-widget-template-migration.md",
        "schemas/batch-object-definition.template.json",
        "schemas/intermediate-analysis.schema.json");

    private static final Set<String> SKIPPED_DIRS = Set.of("build", "dist", "node_modules", "bin", ".gradle", ".idea", ".vscode", "target");

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        "yaml", "yml", "json", "js", "jsx", "ts", "tsx", "java", "properties", "gradle", "kts", "scss", "css", "html", "ftl", "md", "txt", "xml");

    /** The text files under client-extensions/ and fragments/, keyed by workspace-relative path with '/' separators. */
    public static MigrationPlan read(Path workspace) throws IOException {
        MigrationPlan plan = new MigrationPlan();
        TreeMap<String, String> files = new TreeMap<>();
        for (String root : ROOTS) {
            Path dir = workspace.resolve(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) paths::iterator) {
                    if (Files.isRegularFile(p) && isText(p) && !inSkippedDir(workspace.relativize(p))) {
                        files.put(workspace.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
                    }
                }
            }
        }
        plan.generatedFiles.putAll(files);
        return plan;
    }

    /** Writes the knowledge base (playbooks, blueprints, schemas) of this version into dir; returns the files written. */
    public static List<String> exportKnowledge(Path dir) throws IOException {
        for (String file : KNOWLEDGE) {
            try (InputStream in = WorkspaceFiles.class.getResourceAsStream("/knowledge/" + file)) {
                if (in == null) {
                    throw new IOException("Knowledge file missing from the jar: " + file);
                }
                Path target = dir.resolve(file);
                Files.createDirectories(target.getParent());
                Files.write(target, in.readAllBytes());
            }
        }
        return KNOWLEDGE;
    }

    private static boolean isText(Path p) {
        String name = p.getFileName().toString();
        if (name.equals("Dockerfile")) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    private static boolean inSkippedDir(Path relative) {
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (SKIPPED_DIRS.contains(relative.getName(i).toString())) {
                return true;
            }
        }
        return false;
    }
}
