package io.github.danieldefrancisco_lr.migrator.cli;

import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator;
import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator.MigrationPlan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A generated plan saved as text, so it can be reviewed (and edited) before 'apply' writes it, without calling
 * Gemini again. Files use the same '=== FILE: path ===' delimiters as Michelangelo's answers.
 */
public class PlanFile {

    private static final String BLUEPRINT_START = "=== BLUEPRINT ===\n";
    private static final String BLUEPRINT_END = "\n=== END BLUEPRINT ===\n";

    public static void write(Path file, MigrationPlan plan) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(BLUEPRINT_START).append(plan.architectureBlueprint != null ? plan.architectureBlueprint : "").append(BLUEPRINT_END).append("\n");
        for (Map.Entry<String, String> e : plan.generatedFiles.entrySet()) {
            sb.append("=== FILE: ").append(e.getKey()).append(" ===\n").append(e.getValue()).append("\n=== END FILE ===\n\n");
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    public static MigrationPlan read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        MigrationPlan plan = new MigrationPlan();
        String files = text;
        int start = text.indexOf(BLUEPRINT_START);
        int end = text.indexOf(BLUEPRINT_END);
        if (start != -1 && end > start) {
            plan.architectureBlueprint = text.substring(start + BLUEPRINT_START.length(), end);
            files = text.substring(end + BLUEPRINT_END.length());
        }
        AgentOrchestrator.parseGeneratedFiles(files, plan.generatedFiles);
        return plan;
    }
}
