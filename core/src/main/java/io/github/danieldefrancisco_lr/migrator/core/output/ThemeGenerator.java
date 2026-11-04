package io.github.danieldefrancisco_lr.migrator.core.output;

import io.github.danieldefrancisco_lr.migrator.core.agent.AgentOrchestrator.MigrationPlan;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.ThemeInventory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns a legacy theme into a theme client extension without AI, with the format validated on DXP 2026.q3:
 * themeCSS compiled by the workspace from src/css on the "styled" design pack, the theme's own Style Book tokens,
 * its images and fonts, and its JavaScript as globalJS. The base theme that the WAR build merged in (Clay sources,
 * the default favicon, the Clay icon set) is left out: the design pack provides the current one.
 */
public class ThemeGenerator {

    private static final Pattern IMPORT = Pattern.compile("@(?:import|use)\\s+([^;]+);");
    private static final Pattern QUOTED = Pattern.compile("['\"]([^'\"]+)['\"]");
    private static final Pattern URL = Pattern.compile("url\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)");

    /** Adds the theme client extension to the plan; returns its folder (client-extensions/[id]). */
    public static String generate(ThemeInventory theme, MigrationPlan plan) throws IOException {
        String id = slug(theme.themeId);
        String dir = "client-extensions/" + id;
        Path root = Paths.get(theme.themeRoot);
        Path css = root.resolve("css");

        // 1. SCSS: the customization hooks and the theme's own folders, then the base partials they import
        Set<String> copied = new TreeSet<>();
        Deque<String> toScan = new ArrayDeque<>();
        if (Files.isDirectory(css)) {
            try (Stream<Path> files = Files.walk(css)) {
                for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile).sorted()::iterator) {
                    String rel = css.relativize(p).toString().replace('\\', '/');
                    String top = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : null;
                    if (top == null) {
                        if (isHook(rel)) {
                            copied.add(rel);
                            toScan.add(rel);
                        }
                    } else if (!isBaseDir(top)) {
                        copied.add(rel);
                        if (rel.endsWith(".scss") || isExtensionlessPartial(rel)) {
                            toScan.add(rel);
                        } else {
                            // Plain CSS, fonts and images of the theme's folders are also served, for url() and CSS @imports
                            plan.copiedFiles.put(dir + "/assets/css/" + rel, p.toString());
                        }
                    }
                }
            }
        }
        while (!toScan.isEmpty()) {
            String rel = toScan.poll();
            String content = Files.readString(css.resolve(rel), StandardCharsets.UTF_8);
            for (String imported : imports(content)) {
                String resolved = resolve(css, rel, imported);
                if (resolved != null && !copied.contains(resolved) && !resolved.startsWith("clay/")) {
                    copied.add(resolved);
                    toScan.add(resolved);
                }
            }
        }
        // The design pack brings its own main.scss and clay.scss, so imports a theme added to them (e.g. Formed's
        // "@import 'formed/index';" at the end of main.scss) move to the hook each entry file imports last
        Map<String, String> hookAdditions = new java.util.LinkedHashMap<>();
        for (String[] entry : new String[][] {{"main.scss", "_custom.scss"}, {"clay.scss", "_clay_custom.scss"}}) {
            if (!Files.isRegularFile(css.resolve(entry[0]))) {
                continue;
            }
            StringBuilder extra = new StringBuilder();
            for (String imported : imports(Files.readString(css.resolve(entry[0]), StandardCharsets.UTF_8))) {
                if (!isBaseEntryImport(imported)) {
                    extra.append("@import '").append(imported).append("';\n");
                    String resolved = resolve(css, entry[0], imported);
                    if (resolved != null && copied.add(resolved)) {
                        toScan.add(resolved);
                    }
                }
            }
            if (extra.length() > 0) {
                hookAdditions.put(entry[1], "\n// Imported by the legacy theme's " + entry[0] + ", which the design pack replaces\n" + extra);
            }
        }
        while (!toScan.isEmpty()) {
            String rel = toScan.poll();
            String content = Files.readString(css.resolve(rel), StandardCharsets.UTF_8);
            for (String imported : imports(content)) {
                String resolved = resolve(css, rel, imported);
                if (resolved != null && !copied.contains(resolved) && !resolved.startsWith("clay/")) {
                    copied.add(resolved);
                    toScan.add(resolved);
                }
            }
        }
        for (Map.Entry<String, String> hook : hookAdditions.entrySet()) {
            String original = Files.isRegularFile(css.resolve(hook.getKey())) ?
                Files.readString(css.resolve(hook.getKey()), StandardCharsets.UTF_8) : "";
            copied.remove(hook.getKey());
            plan.generatedFiles.put(dir + "/src/css/" + hook.getKey(), original + hook.getValue());
        }

        Set<String> baseImages = new java.util.HashSet<>(theme.baseImages);
        for (String rel : copied) {
            plan.copiedFiles.put(dir + "/src/css/" + asPartial(css, rel), css.resolve(rel).toString());
            if (rel.endsWith(".scss") || rel.endsWith(".css")) {
                // Base theme images the copied styles use, e.g. url('../images/aui/loading.gif')
                Matcher url = URL.matcher(Files.readString(css.resolve(rel), StandardCharsets.UTF_8));
                while (url.find()) {
                    int images = url.group(1).indexOf("images/");
                    String image = images < 0 ? null : url.group(1).substring(images).replaceAll("[?#].*$", "");
                    if (image != null && baseImages.contains(image)) {
                        plan.copiedFiles.put(dir + "/assets/" + image, root.resolve(image).toString());
                    }
                }
            }
        }

        // 2. Style Book tokens, images and fonts, JavaScript
        if (theme.tokenDefinition != null) {
            plan.copiedFiles.put(dir + "/src/frontend-token-definition.json", root.resolve(theme.tokenDefinition).toString());
        }
        for (String asset : theme.assets) {
            plan.copiedFiles.put(dir + "/assets/" + asset, root.resolve(asset).toString());
        }
        for (String js : theme.customJs) {
            plan.copiedFiles.put(dir + "/assets/" + js, root.resolve(js).toString());
        }

        plan.generatedFiles.put(dir + "/client-extension.yaml", yaml(id, theme));
        plan.generatedFiles.put(dir + "/package.json",
            "{\n\t\"liferayDesignPack\": {\n\t\t\"baseTheme\": \"styled\"\n\t},\n\t\"main\": \"package.json\",\n\t\"name\": \"" + id +
            "\",\n\t\"private\": true,\n\t\"version\": \"1.0.0\"\n}\n");
        return dir;
    }

    static String yaml(String id, ThemeInventory theme) {
        StringBuilder sb = new StringBuilder();
        sb.append("assemble:\n    - from: assets\n      into: static\n\n");
        sb.append(id).append("-css:\n");
        sb.append("    clayURL: css/clay.css\n");
        if (theme.tokenDefinition != null) {
            sb.append("    frontendTokenDefinitionJSON: src/frontend-token-definition.json\n");
        }
        sb.append("    mainURL: css/main.css\n");
        sb.append("    name: ").append(theme.themeName).append(" CSS\n");
        sb.append("    type: themeCSS\n");
        if (theme.customFavicon != null) {
            sb.append("\n").append(id).append("-favicon:\n");
            sb.append("    name: ").append(theme.themeName).append(" Favicon\n");
            sb.append("    type: themeFavicon\n");
            sb.append("    url: ").append(theme.customFavicon).append("\n");
        }
        for (String js : theme.customJs) {
            String name = js.substring(js.lastIndexOf('/') + 1, js.length() - 3);
            sb.append("\n").append(id).append("-").append(name.toLowerCase().replaceAll("[^a-z0-9]+", "-")).append("-js:\n");
            sb.append("    name: ").append(theme.themeName).append(" ").append(name).append(".js\n");
            sb.append("    scriptLocation: bottom\n");
            sb.append("    type: globalJS\n");
            sb.append("    url: ").append(js).append("\n");
        }
        return sb.toString();
    }

    /** The files a SCSS @import or @use names (plain url() and http imports are skipped). */
    static List<String> imports(String scss) {
        List<String> result = new java.util.ArrayList<>();
        String code = scss.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        Matcher m = IMPORT.matcher(code);
        while (m.find()) {
            Matcher q = QUOTED.matcher(m.group(1));
            while (q.find()) {
                String name = q.group(1);
                if (!name.startsWith("http") && !name.startsWith("//")) {
                    result.add(name);
                }
            }
        }
        return result;
    }

    /** Resolves an import the way Sass does (relative to the file, then to css/; partials with '_'; folder _index). */
    static String resolve(Path css, String from, String imported) {
        String fromDir = from.contains("/") ? from.substring(0, from.lastIndexOf('/') + 1) : "";
        for (String base : new String[] {fromDir, ""}) {
            String path = normalize(base + imported);
            String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
            String name = path.substring(dir.length());
            String[] candidates = name.endsWith(".css") || name.endsWith(".scss") ?
                new String[] {dir + name, dir + "_" + name} :
                new String[] {dir + "_" + name + ".scss", dir + name + ".scss", dir + name + ".css", path + "/_index.scss", path + "/index.scss",
                    dir + "_" + name};
            for (String c : candidates) {
                if (Files.isRegularFile(css.resolve(c))) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * The workspace compiles every .scss whose name does not start with '_' as a stylesheet of its own, and partials
     * such as formed/forms.scss fail there (validated: "Undefined variable"). Sass resolves @import 'forms' to
     * _forms.scss too, so the theme's sub-folder files are written as partials.
     */
    static String asPartial(Path css, String rel) {
        int slash = rel.lastIndexOf('/');
        String name = rel.substring(slash + 1);
        if (isExtensionlessPartial(rel)) {
            // The 7.x compiler accepted partials without extension (Formed's formed/_mixins); Dart Sass needs .scss
            return rel + ".scss";
        }
        if (slash < 0 || !name.endsWith(".scss") || name.startsWith("_") ||
            Files.exists(css.resolve(rel.substring(0, slash + 1) + "_" + name))) {
            return rel;
        }
        return rel.substring(0, slash + 1) + "_" + name;
    }

    /** A Sass partial saved without extension, e.g. formed/_mixins. */
    static boolean isExtensionlessPartial(String rel) {
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        return name.startsWith("_") && !name.contains(".");
    }

    private static String normalize(String path) {
        Deque<String> parts = new ArrayDeque<>();
        for (String p : path.split("/")) {
            if (p.isEmpty() || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                parts.pollLast();
            } else {
                parts.add(p);
            }
        }
        return String.join("/", parts);
    }

    /** What the base theme's main.scss and clay.scss import; anything else was added by the theme. */
    static boolean isBaseEntryImport(String imported) {
        String name = imported.replaceAll("^\\./", "").replaceAll("^_", "").replaceAll("\\.scss$", "");
        return name.startsWith("clay/") || Set.of("imports", "base", "portal", "taglib", "application", "layout", "navigation",
            "portlet", "widget", "extras", "custom", "clay_variables", "clay_custom", "liferay_variables", "liferay_variables_custom",
            "liferay_custom", "custom_properties", "atlas_variables", "clay").contains(name);
    }

    private static boolean isHook(String file) {
        return Set.of("_custom.scss", "_clay_variables.scss", "_clay_custom.scss", "_liferay_variables_custom.scss",
            "_custom_properties.scss").contains(file);
    }

    private static boolean isBaseDir(String top) {
        return Set.of("application", "base", "clay", "compat", "components", "custom_properties", "layout", "navigation",
            "portal", "portlet", "taglib").contains(top);
    }

    public static String slug(String s) {
        String slug = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return slug.endsWith("theme") ? slug : slug + "-theme";
    }
}
