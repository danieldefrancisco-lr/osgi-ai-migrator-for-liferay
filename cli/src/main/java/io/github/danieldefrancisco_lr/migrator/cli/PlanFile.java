package io.github.danieldefrancisco_lr.migrator.cli;

import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator;
import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator.MigrationPlan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A generated plan saved as text, so it can be reviewed (and edited) before 'apply' writes it, without calling
 * Gemini again. Files use the same '=== FILE: path ===' delimiters as Michelangelo's answers.
 */
public class PlanFile {

    private static final String BLUEPRINT_START = "=== BLUEPRINT ===\n";
    private static final String BLUEPRINT_END = "\n=== END BLUEPRINT ===\n";
    /** A file copied as it is from the legacy module (e.g. a theme image): '=== COPY: destination <= source'. */
    private static final String COPY = "=== COPY: ";
    private static final Pattern COPY_LINE = Pattern.compile("(?m)^=== COPY: (.+?) <= (.+)$");

    public static void write(Path file, MigrationPlan plan) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(BLUEPRINT_START).append(plan.architectureBlueprint != null ? plan.architectureBlueprint : "").append(BLUEPRINT_END).append("\n");
        for (Map.Entry<String, String> e : plan.generatedFiles.entrySet()) {
            sb.append("=== FILE: ").append(e.getKey()).append(" ===\n").append(e.getValue()).append("\n=== END FILE ===\n\n");
        }
        for (Map.Entry<String, String> e : plan.copiedFiles.entrySet()) {
            sb.append(COPY).append(e.getKey()).append(" <= ").append(e.getValue()).append("\n");
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
        Matcher copy = COPY_LINE.matcher(files);
        while (copy.find()) {
            plan.copiedFiles.put(copy.group(1).trim(), copy.group(2).trim());
        }
        AgentOrchestrator.parseGeneratedFiles(COPY_LINE.matcher(files).replaceAll(""), plan.generatedFiles);
        return plan;
    }
}
