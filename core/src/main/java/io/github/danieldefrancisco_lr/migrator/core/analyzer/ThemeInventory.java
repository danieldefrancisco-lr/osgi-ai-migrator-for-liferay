package io.github.danieldefrancisco_lr.migrator.core.analyzer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a legacy theme WAR is made of, separating the theme's own files from the base theme that the build merged in.
 * Built WARs ship the whole base theme (Clay sources, hundreds of icons, the default favicon); the theme source
 * (src/main/webapp) ships only the theme's files. Both are handled.
 */
public class ThemeInventory {

    /** A configurable setting of liferay-look-and-feel.xml (e.g. show-header). */
    public static class Setting {
        public final String key;
        public final String type;
        public final String value;
        public final boolean configurable;

        Setting(String key, String type, String value, boolean configurable) {
            this.key = key;
            this.type = type;
            this.value = value;
            this.configurable = configurable;
        }
    }

    public String themeId;
    public String themeName;
    /** Folder that holds css/, images/, templates/ and WEB-INF/, as an absolute path. */
    public String themeRoot;
    /** Every SCSS/CSS source under css/ (compiled .css and .map files excluded), relative to the theme root. */
    public final List<String> scssFiles = new ArrayList<>();
    /** The theme's own SCSS: the customization hooks and the folders that are not part of the base theme. */
    public final List<String> customScss = new ArrayList<>();
    /** Images and fonts the CSS can reference (relative to the theme root). */
    public final List<String> assets = new ArrayList<>();
    /** JavaScript files with code (the empty main.js of the theme generator is left out). */
    public final List<String> customJs = new ArrayList<>();
    /** The scripts of customJs that use AUI, which is not loaded outside classic themes. */
    public final List<String> usesAui = new ArrayList<>();
    public final List<String> templates = new ArrayList<>();
    public final List<Setting> settings = new ArrayList<>();
    /** WEB-INF/frontend-token-definition.json, if the theme already defines Style Book tokens. */
    public String tokenDefinition;
    public int tokenCount;
    /** images/favicon.ico when it is not Liferay's default one. */
    public String customFavicon;
    /** Base-theme files that are deliberately not migrated, with the reason. */
    public final List<String> notMigrated = new ArrayList<>();
    /** Images of the base theme's folders (images/aui, images/common...), copied only if the theme's SCSS uses them. */
    public final List<String> baseImages = new ArrayList<>();
    /** images/icons.svg outside the Clay folders: possibly the theme's own icon set (e.g. Minium's). */
    public String candidateSpritemap;

    /** Image folders every 7.x base theme ships. */
    static final Set<String> BASE_IMAGE_DIRS = Set.of(
        "add_content", "api", "application", "arrows", "aui", "blogs", "bookmarks", "calendar", "common", "control_panel",
        "diff", "dockbar", "document_library", "emoticons", "file_system", "forms", "image_gallery_display", "journal",
        "language", "login", "mail", "message_boards", "messages", "navigation", "portlet", "portlet_hello_world",
        "progress_bar", "ratings", "shadow", "shopping", "social", "social_bookmarks", "staging_bar", "states", "trees",
        "users_admin", "wiki");
    /** Theme previews and the base theme's spacer, which no page uses. */
    static final Set<String> PREVIEW_IMAGES = Set.of("images/screenshot.png", "images/thumbnail.png", "images/spacer.png");

    /** Base theme SCSS folders and entry files; everything else under css/ belongs to the theme. */
    static final Set<String> BASE_SCSS_DIRS = Set.of(
        "application", "base", "clay", "compat", "components", "custom_properties", "layout", "navigation", "portal",
        "portlet", "taglib");
    static final Set<String> CUSTOM_HOOKS = Set.of(
        "_custom.scss", "_clay_variables.scss", "_clay_custom.scss", "_liferay_variables_custom.scss", "_custom_properties.scss");

    /** MD5 of the favicon.ico that every Liferay 7.x base theme ships. */
    static final String DEFAULT_FAVICON_MD5 = "c01ead7ded011886d0ba64364e623e16";

    private static final Pattern THEME = Pattern.compile("<theme\\s+id=\"([^\"]+)\"\\s+name=\"([^\"]+)\"");
    private static final Pattern SETTING = Pattern.compile("<setting\\s+([^>]*?)/?>");
    private static final Pattern ATTRIBUTE = Pattern.compile("(\\w[\\w-]*)=\"([^\"]*)\"");

    /**
     * Builds the inventory from the files of a module. lookAndFeel is the path of WEB-INF/liferay-look-and-feel.xml
     * relative to moduleDir; paths are the module's files relative to moduleDir.
     */
    public static ThemeInventory build(File moduleDir, String lookAndFeel, List<String> paths) throws Exception {
        ThemeInventory theme = new ThemeInventory();
        String prefix = lookAndFeel.substring(0, lookAndFeel.length() - "WEB-INF/liferay-look-and-feel.xml".length());
        theme.themeRoot = new File(moduleDir, prefix).getCanonicalPath();

        String xml = read(new File(moduleDir, lookAndFeel));
        Matcher t = THEME.matcher(xml);
        if (t.find()) {
            theme.themeId = t.group(1);
            theme.themeName = t.group(2);
        }
        Matcher s = SETTING.matcher(xml);
        while (s.find()) {
            String key = null, type = "text", value = "";
            boolean configurable = false;
            Matcher a = ATTRIBUTE.matcher(s.group(1));
            while (a.find()) {
                switch (a.group(1)) {
                    case "key": key = a.group(2); break;
                    case "type": type = a.group(2); break;
                    case "value": value = a.group(2); break;
                    case "configurable": configurable = Boolean.parseBoolean(a.group(2)); break;
                    default: break;
                }
            }
            if (key != null) {
                theme.settings.add(new Setting(key, type, value, configurable));
            }
        }

        for (String path : paths) {
            if (!path.startsWith(prefix)) {
                continue;
            }
            String rel = path.substring(prefix.length());
            if (rel.startsWith("css/") && (rel.endsWith(".scss") || (rel.endsWith(".css") && rel.contains("/") && !isCompiled(rel)))) {
                theme.scssFiles.add(rel);
                String inCss = rel.substring("css/".length());
                String top = inCss.contains("/") ? inCss.substring(0, inCss.indexOf('/')) : null;
                if (top == null ? CUSTOM_HOOKS.contains(inCss) : !BASE_SCSS_DIRS.contains(top)) {
                    theme.customScss.add(rel);
                }
            } else if (rel.startsWith("images/") || rel.startsWith("font/") || rel.startsWith("fonts/")) {
                if (rel.equals("images/favicon.ico")) {
                    if (DEFAULT_FAVICON_MD5.equals(md5(new File(theme.themeRoot, rel)))) {
                        theme.notMigrated.add(rel + ": Liferay's default favicon");
                        continue;
                    }
                    theme.customFavicon = rel;
                }
                if (rel.startsWith("images/clay/") || rel.startsWith("images/lexicon/")) {
                    if (rel.endsWith("/icons.svg")) {
                        theme.notMigrated.add(rel + ": the Clay icon set of the base theme (registering it as a spritemap would replace the current icons)");
                    }
                    continue;
                }
                if (rel.equals("images/icons.svg")) {
                    theme.candidateSpritemap = rel;
                }
                String imageDir = rel.startsWith("images/") && rel.indexOf('/', 7) > 0 ? rel.substring(7, rel.indexOf('/', 7)) : null;
                if (imageDir != null && BASE_IMAGE_DIRS.contains(imageDir)) {
                    // Base theme images are copied only when the theme's SCSS references them (see ThemeGenerator)
                    theme.baseImages.add(rel);
                    continue;
                }
                if (PREVIEW_IMAGES.contains(rel)) {
                    continue;
                }
                theme.assets.add(rel);
            } else if (rel.startsWith("js/") && rel.endsWith(".js")) {
                if (isEmptyMainJs(read(new File(theme.themeRoot, rel)))) {
                    theme.notMigrated.add(rel + ": the empty main.js of the theme generator");
                } else {
                    theme.customJs.add(rel);
                    String js = read(new File(theme.themeRoot, rel));
                    if (js.contains("AUI(") || js.contains("A.one(") || js.contains("A.all(")) {
                        theme.usesAui.add(rel);
                    }
                }
            } else if (rel.startsWith("templates/") && rel.endsWith(".ftl")) {
                theme.templates.add(rel);
            } else if (rel.equals("WEB-INF/frontend-token-definition.json")) {
                theme.tokenDefinition = rel;
                theme.tokenCount = countTokens(read(new File(theme.themeRoot, rel)));
            }
        }
        theme.scssFiles.sort(null);
        theme.customScss.sort(null);
        theme.assets.sort(null);
        theme.customJs.sort(null);
        theme.templates.sort(null);
        theme.notMigrated.sort(null);
        theme.baseImages.sort(null);
        return theme;
    }

    /** One line per finding, for the analysis report and the prompts. */
    public List<String> summary() {
        List<String> lines = new ArrayList<>();
        lines.add("Theme " + themeName + " (" + themeId + ")");
        lines.add(scssFiles.size() + " SCSS sources, " + customScss.size() + " of the theme's own: " + abbreviate(customScss));
        lines.add(tokenDefinition != null ? tokenCount + " Style Book tokens in " + tokenDefinition : "no Style Book tokens");
        lines.add(assets.size() + " images and fonts of the theme" + (customFavicon != null ? ", custom favicon" : "") +
            " (+ " + baseImages.size() + " base theme images, copied only if its SCSS uses them)");
        if (candidateSpritemap != null) {
            lines.add("Possible own icon set: " + candidateSpritemap + " (copied, not registered as a spritemap: check it before replacing the Clay icons)");
        }
        lines.add("JavaScript with code: " + (customJs.isEmpty() ? "none" : String.join(", ", customJs)) +
            (usesAui.isEmpty() ? "" : " (uses AUI, so it must be ported or dropped: " + String.join(", ", usesAui) + ")"));
        lines.add("Templates: " + String.join(", ", templates));
        List<String> configurable = new ArrayList<>();
        for (Setting s : settings) {
            if (s.configurable) {
                configurable.add(s.key);
            }
        }
        lines.add("Configurable settings: " + (configurable.isEmpty() ? "none" : String.join(", ", configurable)));
        for (String n : notMigrated) {
            lines.add("Not migrated: " + n);
        }
        return lines;
    }

    private static String abbreviate(List<String> files) {
        if (files.size() <= 8) {
            return String.join(", ", files);
        }
        return String.join(", ", files.subList(0, 8)) + " (+" + (files.size() - 8) + " more)";
    }

    private static boolean isCompiled(String rel) {
        // Plain .css files inside source folders (e.g. vendor/fontawesome-free/css/all.css) are sources; the top-level
        // clay.css / main.css / *_rtl.css are build output
        return !rel.substring("css/".length()).contains("/");
    }

    /** The theme generator's main.js: three empty callbacks and comments. */
    static boolean isEmptyMainJs(String js) {
        String code = js.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "").replaceAll("\\s+", "");
        String empty = "AUI().ready(function(){});Liferay.Portlet.ready(function(_portletId,_node){});Liferay.on('allPortletsReady',function(){});";
        return code.isEmpty() || code.equals(empty) ||
            code.equals(empty.replace("_portletId,_node", "portletId,node"));
    }

    private static int countTokens(String json) {
        Matcher m = Pattern.compile("\"editorType\"\\s*:").matcher(json);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static String md5(File f) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(Files.readAllBytes(f.toPath()));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
