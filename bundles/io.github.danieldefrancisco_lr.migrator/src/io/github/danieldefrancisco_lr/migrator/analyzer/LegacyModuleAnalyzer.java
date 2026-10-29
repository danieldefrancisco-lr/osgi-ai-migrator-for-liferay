package io.github.danieldefrancisco_lr.migrator.analyzer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;

/**
 * Donatello (The Analyzer) - Reverse-engineers legacy OSGi resources using Eclipse Resource & AST APIs.
 * Detects legacy Liferay platform version from gradle.properties or pom.xml.
 */
public class LegacyModuleAnalyzer {

    public static class ModuleAnalysisResult {
        public String moduleName;
        public String projectPath;
        public String liferayWorkspaceRoot;
        public String sourceLiferayVersion;
        public boolean hasServiceBuilder;
        public boolean hasJspPortlet;
        public boolean hasTheme;
        public List<String> detectedEntities = new ArrayList<>();
        public List<String> detectedPortlets = new ArrayList<>();
        public List<String> detectedListeners = new ArrayList<>();
        public List<String> detectedSchedulers = new ArrayList<>();
        public Map<String, String> rawSourceExtracts = new HashMap<>();
    }

    public static ModuleAnalysisResult analyzeResource(IResource resource) throws CoreException {
        ModuleAnalysisResult result = new ModuleAnalysisResult();
        IProject project = resource.getProject();
        result.moduleName = project != null ? project.getName() : resource.getName();
        result.projectPath = resource.getLocation().toOSString();

        // 1. Detect Liferay Workspace Root and Liferay Platform Version
        detectLiferayWorkspaceAndVersion(resource, result);

        // 2. Scan Module Resources
        resource.accept(new IResourceVisitor() {
            @Override
            public boolean visit(IResource res) throws CoreException {
                if (res.getType() == IResource.FILE) {
                    IFile file = (IFile) res;
                    String fileName = file.getName();

                    // Detect Service Builder
                    if ("service.xml".equalsIgnoreCase(fileName)) {
                        result.hasServiceBuilder = true;
                        String content = readFileContent(file);
                        result.rawSourceExtracts.put("service.xml", content);
                        extractEntitiesFromServiceXml(content, result.detectedEntities);

                        // Fallback version detection from service-builder DTD
                        if (result.sourceLiferayVersion == null) {
                            result.sourceLiferayVersion = extractVersionFromServiceXmlDtd(content);
                        }
                    }
                    // Detect JSPs
                    else if (fileName.endsWith(".jsp") || fileName.endsWith(".jspf")) {
                        result.hasJspPortlet = true;
                        result.rawSourceExtracts.put("jsp:" + file.getProjectRelativePath(), readFileContent(file));
                    }
                    // Detect Java Sources (Portlets, Listeners, Wrappers)
                    else if (fileName.endsWith(".java")) {
                        String content = readFileContent(file);
                        if (content.contains("@Component") && content.contains("Portlet.class")) {
                            result.hasJspPortlet = true;
                            result.detectedPortlets.add(fileName);
                            result.rawSourceExtracts.put("java:portlet:" + fileName, content);
                        }
                        // Detect referenced model entities from imports (e.g. import ...model.Assignment;)
                        extractModelImports(content, result.detectedEntities);

                        if (content.contains("ModelListener") || content.contains("BaseModelListener")) {
                            result.detectedListeners.add(fileName);
                            result.rawSourceExtracts.put("java:listener:" + fileName, content);
                        }
                        if (content.contains("ServiceWrapper") || content.contains("LocalServiceWrapper")) {
                            result.rawSourceExtracts.put("java:wrapper:" + fileName, content);
                        }
                        if (content.contains("SchedulerEntry") || content.contains("BaseMessageListener") || content.contains("DispatchTaskExecutor")) {
                            result.detectedSchedulers.add(fileName);
                            result.rawSourceExtracts.put("java:scheduler:" + fileName, content);
                        }
                    }
                    // Detect Themes
                    else if ("portal_normal.ftl".equalsIgnoreCase(fileName) || "liferay-look-and-feel.xml".equalsIgnoreCase(fileName)) {
                        result.hasTheme = true;
                        result.rawSourceExtracts.put("theme:" + fileName, readFileContent(file));
                    }
                    // Inspect module's build.gradle for version clues
                    else if ("build.gradle".equalsIgnoreCase(fileName) && result.sourceLiferayVersion == null) {
                        String content = readFileContent(file);
                        result.sourceLiferayVersion = extractVersionFromBuildGradle(content);
                    }
                }
                return true;
            }
        });

        if (result.sourceLiferayVersion == null) {
            result.sourceLiferayVersion = "Liferay 7.x (Generic)";
        }

        return result;
    }

    private static void detectLiferayWorkspaceAndVersion(IResource resource, ModuleAnalysisResult result) {
        File current = resource.getLocation().toFile();
        if (current.isFile()) {
            current = current.getParentFile();
        }

        // Traverse upwards to find Liferay Workspace root (contains gradle.properties, settings.gradle, or pom.xml)
        while (current != null) {
            File gradleProps = new File(current, "gradle.properties");
            File settingsGradle = new File(current, "settings.gradle");
            File pomXml = new File(current, "pom.xml");

            if (gradleProps.exists() || settingsGradle.exists() || pomXml.exists()) {
                result.liferayWorkspaceRoot = current.getAbsolutePath();

                // Check gradle.properties for liferay.workspace.product
                if (gradleProps.exists()) {
                    String version = parseGradleProperties(gradleProps);
                    if (version != null) {
                        result.sourceLiferayVersion = version;
                        return;
                    }
                }

                // Check pom.xml for Maven workspace
                if (pomXml.exists()) {
                    String version = parsePomXml(pomXml);
                    if (version != null) {
                        result.sourceLiferayVersion = version;
                        return;
                    }
                }

                // If found workspace root, stop searching parents
                return;
            }
            current = current.getParentFile();
        }

        // Fallback: project directory parent
        if (resource.getProject() != null && resource.getProject().getLocation() != null) {
            File projDir = resource.getProject().getLocation().toFile();
            result.liferayWorkspaceRoot = projDir.getParentFile() != null ? projDir.getParentFile().getAbsolutePath() : projDir.getAbsolutePath();
        }
    }

    private static String parseGradleProperties(File file) {
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            String product = null;
            String targetPlatform = null;

            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("#")) continue;

                if (line.startsWith("liferay.workspace.product=")) {
                    product = line.substring("liferay.workspace.product=".length()).trim();
                } else if (line.startsWith("liferay.workspace.target.platform.version=")) {
                    targetPlatform = line.substring("liferay.workspace.target.platform.version=".length()).trim();
                }
            }

            if (product != null && !product.isEmpty()) {
                return product;
            }
            if (targetPlatform != null && !targetPlatform.isEmpty()) {
                return "Liferay Target Platform " + targetPlatform;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String parsePomXml(File file) {
        try {
            String xml = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            int idx = xml.indexOf("<liferay.bom.version>");
            if (idx != -1) {
                int end = xml.indexOf("</liferay.bom.version>", idx);
                if (end != -1) {
                    return "Liferay BOM " + xml.substring(idx + "<liferay.bom.version>".length(), end).trim();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String extractVersionFromServiceXmlDtd(String xml) {
        int idx = xml.indexOf("Service Builder ");
        if (idx != -1) {
            int end = xml.indexOf("//EN", idx);
            if (end != -1) {
                return "Liferay Service Builder DTD " + xml.substring(idx + "Service Builder ".length(), end).trim();
            }
        }
        return null;
    }

    private static String extractVersionFromBuildGradle(String content) {
        int idx = content.indexOf("release.dxp.api");
        if (idx != -1) {
            int vIdx = content.indexOf("version:", idx);
            if (vIdx != -1) {
                int quoteStart = content.indexOf("\"", vIdx);
                if (quoteStart == -1) quoteStart = content.indexOf("'", vIdx);
                if (quoteStart != -1) {
                    char q = content.charAt(quoteStart);
                    int quoteEnd = content.indexOf(q, quoteStart + 1);
                    if (quoteEnd != -1) {
                        return "release.dxp.api " + content.substring(quoteStart + 1, quoteEnd).trim();
                    }
                }
            }
        }
        return null;
    }

    private static void extractModelImports(String content, List<String> entities) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ") && trimmed.contains(".model.") && trimmed.endsWith(";")) {
                String fullClassName = trimmed.substring(7, trimmed.length() - 1).trim();
                String simpleName = fullClassName.substring(fullClassName.lastIndexOf('.') + 1);
                if (!simpleName.isEmpty() && !simpleName.endsWith("Model") && !simpleName.endsWith("Wrapper") &&
                    !simpleName.endsWith("Soap") && !simpleName.startsWith("Base") && !entities.contains(simpleName)) {
                    entities.add(simpleName);
                }
            }
        }
    }

    private static void extractEntitiesFromServiceXml(String xml, List<String> entities) {
        int idx = 0;
        while ((idx = xml.indexOf("<entity", idx)) != -1) {
            int nameIdx = xml.indexOf("name=\"", idx);
            if (nameIdx != -1 && nameIdx < xml.indexOf(">", idx)) {
                int endQuote = xml.indexOf("\"", nameIdx + 6);
                if (endQuote != -1) {
                    entities.add(xml.substring(nameIdx + 6, endQuote));
                }
            }
            idx = xml.indexOf(">", idx) + 1;
        }
    }

    private static String readFileContent(IFile file) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getContents(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            // Log or fallback
        }
        return sb.toString();
    }
}
