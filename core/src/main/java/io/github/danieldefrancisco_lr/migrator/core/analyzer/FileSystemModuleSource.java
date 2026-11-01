package io.github.danieldefrancisco_lr.migrator.core.analyzer;

import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.FileVisitor;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleFile;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleSource;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * A module on disk, outside any IDE workspace. Files are visited depth-first with siblings sorted by name,
 * the same order Eclipse uses, so results match the Eclipse plugin's.
 */
public class FileSystemModuleSource implements ModuleSource {

    private final File projectDir;
    private final File selected;

    /** Analyze the whole module directory. */
    public FileSystemModuleSource(File projectDir) {
        this(projectDir, projectDir);
    }

    /** Analyze one folder of the module; paths stay relative to the module directory. */
    public FileSystemModuleSource(File projectDir, File selected) {
        this.projectDir = projectDir.getAbsoluteFile();
        this.selected = selected.getAbsoluteFile();
    }

    @Override
    public String getModuleName() {
        return projectDir.getName();
    }

    @Override
    public File getLocation() {
        return selected;
    }

    @Override
    public File getProjectLocation() {
        return projectDir;
    }

    @Override
    public void visitFiles(FileVisitor visitor) throws Exception {
        visit(selected, visitor);
    }

    private void visit(File file, FileVisitor visitor) throws Exception {
        if (file.isFile()) {
            visitor.visit(new DiskFile(file));
            return;
        }
        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
        for (File child : children) {
            visit(child, visitor);
        }
    }

    private class DiskFile implements ModuleFile {

        private final File file;

        DiskFile(File file) {
            this.file = file;
        }

        @Override
        public String getName() {
            return file.getName();
        }

        @Override
        public String getProjectRelativePath() {
            return projectDir.toPath().relativize(file.toPath()).toString().replace('\\', '/');
        }

        @Override
        public InputStream getContents() throws Exception {
            return Files.newInputStream(file.toPath());
        }
    }
}
