package io.github.danieldefrancisco_lr.migrator.workspace;

import io.github.danieldefrancisco_lr.migrator.Activator;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.FileVisitor;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleFile;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleSource;

import java.io.File;
import java.io.InputStream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

/**
 * Exposes a workspace resource (project or folder) to the analyzer, visiting files in workspace order.
 */
public class EclipseModuleSource implements ModuleSource {

    private final IResource resource;

    public EclipseModuleSource(IResource resource) {
        this.resource = resource;
    }

    @Override
    public String getModuleName() {
        IProject project = resource.getProject();
        return project != null ? project.getName() : resource.getName();
    }

    @Override
    public File getLocation() {
        return resource.getLocation().toFile();
    }

    @Override
    public File getProjectLocation() {
        IProject project = resource.getProject();
        return project != null && project.getLocation() != null ? project.getLocation().toFile() : null;
    }

    @Override
    public void visitFiles(FileVisitor visitor) throws Exception {
        resource.accept(res -> {
            if (res.getType() == IResource.FILE) {
                try {
                    visitor.visit(new WorkspaceFile((IFile) res));
                } catch (CoreException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID, e.getMessage(), e));
                }
            }
            return true;
        });
    }

    private static class WorkspaceFile implements ModuleFile {

        private final IFile file;

        WorkspaceFile(IFile file) {
            this.file = file;
        }

        @Override
        public String getName() {
            return file.getName();
        }

        @Override
        public String getProjectRelativePath() {
            return file.getProjectRelativePath().toString();
        }

        @Override
        public InputStream getContents() throws Exception {
            return file.getContents();
        }
    }
}
