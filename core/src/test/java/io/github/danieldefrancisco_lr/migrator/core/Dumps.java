package io.github.danieldefrancisco_lr.migrator.core;

import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator.MigrationPlan;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleAnalysisResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Deterministic text dumps of analyzer results, plans and written files.
 * Map iteration order is kept on purpose: it decides the order of sections in the Gemini prompts.
 * Copy of the helper in bundles/io.github.danieldefrancisco_lr.migrator.tests (the OSGi tests cannot use core's
 * test classes); keep both in sync.
 */
public class Dumps {

    public static String analysis(ModuleAnalysisResult a) {
        StringBuilder sb = new StringBuilder();
        sb.append("moduleName: ").append(a.moduleName).append("\n");
        sb.append("projectPath: ").append(a.projectPath).append("\n");
        sb.append("liferayWorkspaceRoot: ").append(a.liferayWorkspaceRoot).append("\n");
        sb.append("sourceLiferayVersion: ").append(a.sourceLiferayVersion).append("\n");
        sb.append("hasServiceBuilder: ").append(a.hasServiceBuilder).append("\n");
        sb.append("hasJspPortlet: ").append(a.hasJspPortlet).append("\n");
        sb.append("hasTheme: ").append(a.hasTheme).append("\n");
        sb.append("detectedEntities: ").append(a.detectedEntities).append("\n");
        sb.append("detectedPortlets: ").append(a.detectedPortlets).append("\n");
        sb.append("detectedListeners: ").append(a.detectedListeners).append("\n");
        sb.append("detectedSchedulers: ").append(a.detectedSchedulers).append("\n");
        sb.append("detectedNotifications: ").append(a.detectedNotifications).append("\n");
        sb.append("detectedCustomLogic: ").append(a.detectedCustomLogic).append("\n");
        sb.append("rawSourceExtracts:\n");
        for (Map.Entry<String, String> e : a.rawSourceExtracts.entrySet()) {
            sb.append("--- ").append(e.getKey()).append(" ---\n").append(e.getValue()).append("\n");
        }
        return sb.toString();
    }

    public static String plan(MigrationPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("architectureBlueprint:\n").append(plan.architectureBlueprint).append("\n");
        sb.append("generatedFiles:\n");
        for (Map.Entry<String, String> e : plan.generatedFiles.entrySet()) {
            sb.append("--- ").append(e.getKey()).append(" ---\n").append(e.getValue()).append("\n");
        }
        if (!plan.copiedFiles.isEmpty()) {
            sb.append("copiedFiles:\n");
            for (Map.Entry<String, String> e : plan.copiedFiles.entrySet()) {
                sb.append(e.getKey()).append(" <= ").append(e.getValue().replace('\\', '/')).append("\n");
            }
        }
        return sb.toString();
    }

    public static Set<String> listFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                .map(p -> root.relativize(p).toString().replace('\\', '/'))
                .collect(Collectors.toSet());
        }
    }

    /** Files created under root since 'before', sorted by path; zip archives are listed entry by entry. */
    public static String newFiles(Path root, Set<String> before) throws IOException {
        StringBuilder sb = new StringBuilder();
        List<String> paths = new ArrayList<>(listFiles(root));
        paths.removeAll(before);
        paths.sort(Comparator.naturalOrder());
        for (String rel : paths) {
            Path file = root.resolve(rel);
            sb.append("=== ").append(rel).append(" ===\n");
            if (rel.endsWith(".zip")) {
                sb.append(zipListing(file));
            } else if (rel.matches(".*\\.(png|jpe?g|gif|ico|woff2?|ttf|eot|otf)$")) {
                sb.append("(binary, ").append(Files.size(file)).append(" bytes)\n");
            } else {
                sb.append(Files.readString(file, StandardCharsets.UTF_8)).append("\n");
            }
        }
        return sb.toString();
    }

    private static String zipListing(Path zip) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            sb.append("  [zip entry] ").append(e.getKey()).append("\n").append(e.getValue()).append("\n");
        }
        return sb.toString();
    }
}
