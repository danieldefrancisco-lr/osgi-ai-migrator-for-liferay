package io.github.danieldefrancisco_lr.migrator.tests;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares test output with the files in testdata/golden/<case>/, or rewrites them with -Dgolden.update=true.
 * Absolute paths under the temporary fixture copy are replaced with ${ROOT}, and CRLF with LF (the knowledge
 * files' line endings depend on the git checkout), so goldens are machine independent.
 */
public class Golden {

    public static final Path TESTDATA = Paths.get(System.getProperty("testdata.dir", "../../testdata"));

    private static final boolean UPDATE = Boolean.getBoolean("golden.update");
    private static final Pattern ROOT_PATH = Pattern.compile("\\$\\{ROOT\\}[^\\s\"'`)]*");
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

    private final String caseName;
    private final Path dir;
    private final String root;

    public Golden(String caseName, Path fixtureRoot) {
        this.caseName = caseName;
        this.dir = TESTDATA.resolve("golden").resolve(caseName);
        this.root = fixtureRoot.toString();
    }

    public String normalize(String text) {
        String s = text.replace("\r\n", "\n").replace(root, "${ROOT}").replace(root.replace('\\', '/'), "${ROOT}");
        Matcher m = ROOT_PATH.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group().replace('\\', '/')));
        }
        m.appendTail(sb);
        return TIMESTAMP.matcher(sb.toString()).replaceAll("<TIMESTAMP>");
    }

    public void check(String name, String actualText) throws IOException {
        String actual = normalize(actualText);
        Path expectedFile = dir.resolve(name);
        if (UPDATE) {
            Files.createDirectories(expectedFile.getParent());
            Files.writeString(expectedFile, actual, StandardCharsets.UTF_8);
            return;
        }
        if (!Files.exists(expectedFile)) {
            fail("Missing golden file " + expectedFile + " (run with -Dgolden.update=true to create it)");
        }
        String expected = Files.readString(expectedFile, StandardCharsets.UTF_8);
        if (!expected.equals(actual)) {
            Path actualFile = Paths.get(System.getProperty("golden.actual.dir", "target/golden-actual"), caseName, name);
            Files.createDirectories(actualFile.getParent());
            Files.writeString(actualFile, actual, StandardCharsets.UTF_8);
            fail(caseName + "/" + name + " differs from golden: " + firstDifference(expected, actual) +
                "\nActual output written to " + actualFile);
        }
    }

    private static String firstDifference(String expected, String actual) {
        String[] e = expected.split("\n", -1);
        String[] a = actual.split("\n", -1);
        for (int i = 0; i < Math.max(e.length, a.length); i++) {
            String el = i < e.length ? e[i] : "<end of file>";
            String al = i < a.length ? a[i] : "<end of file>";
            if (!el.equals(al)) {
                return "line " + (i + 1) + "\n  expected: " + el + "\n  actual:   " + al;
            }
        }
        return "line endings or trailing characters";
    }
}
