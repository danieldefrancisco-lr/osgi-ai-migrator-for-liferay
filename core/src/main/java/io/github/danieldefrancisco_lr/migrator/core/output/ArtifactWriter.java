package io.github.danieldefrancisco_lr.migrator.core.output;

import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator.MigrationPlan;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleAnalysisResult;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

/**
 * Writes Michelangelo's generated files into the Liferay workspace: fixes misplaced paths, sanitizes batch JSON,
 * zips fragment collections and saves the migration report.
 */
public class ArtifactWriter {

    /** What was written, for the summary shown to the user. */
    public static class WriteResult {
        public final String moduleSlug;
        public final Set<String> fragmentCollections;
        public final Set<String> createdCeDirs;

        WriteResult(String moduleSlug, Set<String> fragmentCollections, Set<String> createdCeDirs) {
            this.moduleSlug = moduleSlug;
            this.fragmentCollections = fragmentCollections;
            this.createdCeDirs = createdCeDirs;
        }
    }

    public static WriteResult writeArtifacts(ModuleAnalysisResult analysisResult, MigrationPlan currentPlan, File targetWorkspaceDir) throws Exception {
        String moduleSlug = analysisResult.moduleName.toLowerCase().replace(".", "-");
        if (moduleSlug.endsWith("-service")) {
            moduleSlug = moduleSlug.substring(0, moduleSlug.length() - 8);
        }
        if (moduleSlug.endsWith("-api")) {
            moduleSlug = moduleSlug.substring(0, moduleSlug.length() - 4);
        }
        String defaultObjectCeDir = "client-extensions/" + moduleSlug + "-object";

        java.util.Set<String> fragmentCollections = new java.util.HashSet<>();
        java.util.Set<String> createdCeDirs = new java.util.HashSet<>();

        for (Map.Entry<String, String> entry : currentPlan.generatedFiles.entrySet()) {
            String relativePath = entry.getKey().trim();

            // Skip standalone validation/action batch files: they fail to deserialize in the Batch Engine.
            // Object Actions belong in the ObjectDefinition ("-actions" files that repeat the definition are kept)
            if (relativePath.endsWith("-validations.batch-engine-data.json") ||
                (relativePath.endsWith(".batch-engine-data.json") &&
                    (entry.getValue().contains("com.liferay.object.admin.rest.dto.v1_0.ObjectAction\"") ||
                     entry.getValue().contains("com.liferay.object.admin.rest.dto.v1_0.ObjectValidationRule\"") ||
                     // Gemini cannot see the legacy data: entries it writes are invented examples
                     entry.getValue().contains("com.liferay.object.rest.dto.v1_0.ObjectEntry\"")))) {
                continue;
            }

            // Re-route fragments mistakenly prefixed with client-extensions/
            if (relativePath.startsWith("client-extensions/") &&
                (relativePath.contains("-fragments/") || relativePath.contains("/collection.json") || relativePath.contains("/fragment.json"))) {
                relativePath = "fragments/" + relativePath.substring("client-extensions/".length());
            }

            // Defensive path normalization
            if (!relativePath.startsWith("client-extensions/") &&
                !relativePath.startsWith("modules/") &&
                !relativePath.startsWith("fragments/")) {

                if (relativePath.contains("custom-element") || relativePath.contains("src/") || relativePath.endsWith(".js") || relativePath.endsWith(".jsx")) {
                    relativePath = "client-extensions/" + moduleSlug + "-custom-element/" + relativePath;
                } else if (relativePath.contains("collection.json") || relativePath.contains("fragment.json")) {
                    relativePath = "fragments/" + moduleSlug + "-fragments/" + relativePath;
                } else {
                    relativePath = defaultObjectCeDir + "/" + relativePath;
                }
            }

            // Track fragment collections
            if (relativePath.startsWith("fragments/")) {
                String sub = relativePath.substring("fragments/".length());
                int slash = sub.indexOf("/");
                if (slash != -1) {
                    fragmentCollections.add(sub.substring(0, slash));
                }
            }

            // Track the client extension folders actually written (after re-routing), which get a MIGRATION.md
            if (relativePath.startsWith("client-extensions/")) {
                String sub = relativePath.substring("client-extensions/".length());
                int slash = sub.indexOf("/");
                if (slash != -1) {
                    createdCeDirs.add(sub.substring(0, slash));
                }
            }

            Path targetPath = Paths.get(targetWorkspaceDir.getAbsolutePath(), relativePath);
            Files.createDirectories(targetPath.getParent());

            String fileContent = entry.getValue();
            if (relativePath.endsWith(".batch-engine-data.json")) {
                fileContent = sanitizeBatchJson(fileContent);
            }

            Files.writeString(targetPath, fileContent);
        }

        // Automatically zip fragment collections for 1-click import into Liferay
        for (String col : fragmentCollections) {
            File colDir = new File(targetWorkspaceDir, "fragments/" + col);
            if (colDir.exists() && colDir.isDirectory()) {
                File zipFile = new File(targetWorkspaceDir, "fragments/" + col + ".zip");
                zipDirectory(colDir, zipFile);
            }
        }

        // Write MIGRATION.md and persist migration history
        String reportMarkdown = MigrationReports.generateMigrationMarkdownReport(analysisResult, currentPlan);

        // 1. Central report in docs/migrations/
        Path centralReportPath = Paths.get(targetWorkspaceDir.getAbsolutePath(), "docs/migrations/" + moduleSlug + "-migration-report.md");
        Files.createDirectories(centralReportPath.getParent());
        Files.writeString(centralReportPath, reportMarkdown);

        // 2. MIGRATION.md inside each created client extension folder
        for (String ceDir : createdCeDirs) {
            Path ceReportPath = Paths.get(targetWorkspaceDir.getAbsolutePath(), "client-extensions/" + ceDir + "/MIGRATION.md");
            Files.writeString(ceReportPath, reportMarkdown);
        }

        return new WriteResult(moduleSlug, fragmentCollections, createdCeDirs);
    }

    private static void zipDirectory(File folderToZip, File zipFile) {
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(zipFile))) {
            Path sourcePath = folderToZip.toPath();
            Files.walk(sourcePath)
                .filter(path -> !Files.isDirectory(path))
                .forEach(path -> {
                    String zipEntryName = sourcePath.relativize(path).toString().replace("\\", "/");
                    try {
                        zos.putNextEntry(new java.util.zip.ZipEntry(zipEntryName));
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (Exception ignored) {}
                });
        } catch (Exception ignored) {}
    }

    private static String sanitizeBatchJson(String content) {
        String sanitized = content;

        // 1. Strip objectLayouts if populated
        int layoutIdx = sanitized.indexOf("\"objectLayouts\"");
        if (layoutIdx != -1) {
            int bracketStart = sanitized.indexOf("[", layoutIdx);
            if (bracketStart != -1) {
                int depth = 0;
                int bracketEnd = -1;
                for (int i = bracketStart; i < sanitized.length(); i++) {
                    char c = sanitized.charAt(i);
                    if (c == '[') depth++;
                    else if (c == ']') {
                        depth--;
                        if (depth == 0) {
                            bracketEnd = i;
                            break;
                        }
                    }
                }
                if (bracketEnd != -1) {
                    sanitized = sanitized.substring(0, bracketStart) + "[]" + sanitized.substring(bracketEnd + 1);
                }
            }
        }

        // 2. Strip objectViews if populated
        int viewsIdx = sanitized.indexOf("\"objectViews\"");
        if (viewsIdx != -1) {
            int bracketStart = sanitized.indexOf("[", viewsIdx);
            if (bracketStart != -1) {
                int depth = 0;
                int bracketEnd = -1;
                for (int i = bracketStart; i < sanitized.length(); i++) {
                    char c = sanitized.charAt(i);
                    if (c == '[') depth++;
                    else if (c == ']') {
                        depth--;
                        if (depth == 0) {
                            bracketEnd = i;
                            break;
                        }
                    }
                }
                if (bracketEnd != -1) {
                    sanitized = sanitized.substring(0, bracketStart) + "[]" + sanitized.substring(bracketEnd + 1);
                }
            }
        }

        // 3. Strip any inline "options" from objectFieldSettings
        int settingsIdx = 0;
        while ((settingsIdx = sanitized.indexOf("\"objectFieldSettings\"", settingsIdx)) != -1) {
            int bStart = sanitized.indexOf("[", settingsIdx);
            if (bStart != -1) {
                int depth = 0;
                int bEnd = -1;
                for (int i = bStart; i < sanitized.length(); i++) {
                    char c = sanitized.charAt(i);
                    if (c == '[') depth++;
                    else if (c == ']') {
                        depth--;
                        if (depth == 0) {
                            bEnd = i;
                            break;
                        }
                    }
                }
                if (bEnd != -1) {
                    String block = sanitized.substring(bStart, bEnd + 1);
                    if (block.contains("\"options\"")) {
                        sanitized = sanitized.substring(0, bStart) + "[]" + sanitized.substring(bEnd + 1);
                        settingsIdx = bStart + 2;
                        continue;
                    }
                }
            }
            settingsIdx += 21;
        }

        // 4. Ensure ListTypeEntry key contains only letters and digits (no spaces, underscores, or hyphens)
        if (sanitized.contains("\"com.liferay.headless.admin.list.type.dto.v1_0.ListTypeDefinition\"")) {
            int keyIdx = 0;
            while ((keyIdx = sanitized.indexOf("\"key\":", keyIdx)) != -1) {
                int q1 = sanitized.indexOf("\"", keyIdx + 6);
                if (q1 != -1) {
                    int q2 = sanitized.indexOf("\"", q1 + 1);
                    if (q2 != -1) {
                        String rawKey = sanitized.substring(q1 + 1, q2);
                        String cleanKey = rawKey.replaceAll("[^a-zA-Z0-9]", "");
                        if (!cleanKey.equals(rawKey) && !cleanKey.isEmpty()) {
                            sanitized = sanitized.substring(0, q1 + 1) + cleanKey + sanitized.substring(q2);
                            keyIdx = q1 + cleanKey.length() + 2;
                            continue;
                        }
                    }
                }
                keyIdx += 7;
            }
        }

        // 5. Ensure folder name has no spaces
        if (sanitized.contains("\"com.liferay.object.admin.rest.dto.v1_0.ObjectFolder\"")) {
            int nameIdx = sanitized.indexOf("\"name\":");
            if (nameIdx != -1) {
                int valStart = sanitized.indexOf("\"", nameIdx + 7);
                if (valStart != -1) {
                    int valEnd = sanitized.indexOf("\"", valStart + 1);
                    if (valEnd != -1) {
                        String rawName = sanitized.substring(valStart + 1, valEnd);
                        String cleanName = rawName.replaceAll("[^a-zA-Z0-9]", "");
                        if (!cleanName.equals(rawName)) {
                            sanitized = sanitized.substring(0, valStart + 1) + cleanName + sanitized.substring(valEnd);
                        }
                    }
                }
            }
        }

        // 6. Strip any reserved system fields from objectFields
        if (sanitized.contains("\"com.liferay.object.admin.rest.dto.v1_0.ObjectDefinition\"")) {
            String[] reservedFields = new String[] {
                "status", "id", "companyId", "groupId", "userId", "userName",
                "createDate", "modifiedDate", "uuid", "statusByUserId", "statusByUserName", "statusDate"
            };
            for (String reserved : reservedFields) {
                sanitized = stripReservedField(sanitized, reserved);
            }
        }

        return sanitized;
    }

    private static String stripReservedField(String content, String fieldName) {
        String marker = "\"name\":";
        int searchIdx = 0;
        while ((searchIdx = content.indexOf(marker, searchIdx)) != -1) {
            int q1 = content.indexOf("\"", searchIdx + marker.length());
            if (q1 == -1) break;
            int q2 = content.indexOf("\"", q1 + 1);
            if (q2 == -1) break;
            String val = content.substring(q1 + 1, q2).trim();
            if (val.equals(fieldName)) {
                // Find enclosing '{'
                int braceCount = 0;
                int startIdx = -1;
                for (int i = searchIdx; i >= 0; i--) {
                    char c = content.charAt(i);
                    if (c == '}') braceCount++;
                    else if (c == '{') {
                        if (braceCount == 0) {
                            startIdx = i;
                            break;
                        }
                        braceCount--;
                    }
                }
                if (startIdx == -1) {
                    searchIdx = q2 + 1;
                    continue;
                }
                // Find enclosing '}'
                braceCount = 0;
                int endIdx = -1;
                for (int i = startIdx; i < content.length(); i++) {
                    char c = content.charAt(i);
                    if (c == '{') braceCount++;
                    else if (c == '}') {
                        braceCount--;
                        if (braceCount == 0) {
                            endIdx = i;
                            break;
                        }
                    }
                }
                if (endIdx == -1) {
                    searchIdx = q2 + 1;
                    continue;
                }

                int removeStart = startIdx;
                int removeEnd = endIdx + 1;
                while (removeEnd < content.length() && Character.isWhitespace(content.charAt(removeEnd))) {
                    removeEnd++;
                }
                if (removeEnd < content.length() && content.charAt(removeEnd) == ',') {
                    removeEnd++;
                } else {
                    int p = startIdx - 1;
                    while (p >= 0 && Character.isWhitespace(content.charAt(p))) p--;
                    if (p >= 0 && content.charAt(p) == ',') {
                        removeStart = p;
                    }
                }
                content = content.substring(0, removeStart) + content.substring(removeEnd);
                searchIdx = removeStart;
                continue;
            }
            searchIdx = q2 + 1;
        }
        return content;
    }
}
