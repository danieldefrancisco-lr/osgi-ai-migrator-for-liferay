package io.github.danieldefrancisco_lr.migrator.intellij;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;

import io.github.danieldefrancisco_lr.migrator.core.analyzer.FileSystemModuleSource;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer;
import io.github.danieldefrancisco_lr.migrator.core.analyzer.LegacyModuleAnalyzer.ModuleAnalysisResult;

import java.io.File;

import org.jetbrains.annotations.NotNull;

/**
 * Project view: right-click a module (or a folder of it) -> OSGi AI Migrator -> Migrate OSGi Module to Client Extensions...
 */
public class MigrateModuleAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        e.getPresentation().setEnabledAndVisible(e.getProject() != null && file != null && file.isDirectory() && file.isInLocalFileSystem());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile selected = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || selected == null) {
            return;
        }
        // The module is the content root that contains the selection (in a Liferay workspace, the module directory)
        VirtualFile contentRoot = ProjectFileIndex.getInstance(project).getContentRootForFile(selected);
        File moduleDir = new File((contentRoot != null ? contentRoot : selected).getPath());
        File selectedDir = new File(selected.getPath());

        ModuleAnalysisResult analysis;
        try {
            analysis = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                () -> LegacyModuleAnalyzer.analyze(new FileSystemModuleSource(moduleDir, selectedDir)),
                "Donatello: Analyzing " + selected.getName(), false, project);
        } catch (Exception ex) {
            Messages.showErrorDialog(project, "Failed to analyze selected module: " + ex.getMessage(), "Analysis Error");
            return;
        }
        new MigrationDialog(project, analysis).show();
    }
}
