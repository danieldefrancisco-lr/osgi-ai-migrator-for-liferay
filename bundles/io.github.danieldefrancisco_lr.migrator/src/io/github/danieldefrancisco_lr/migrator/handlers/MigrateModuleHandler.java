package io.github.danieldefrancisco_lr.migrator.handlers;

import io.github.danieldefrancisco_lr.migrator.analyzer.LegacyModuleAnalyzer;
import io.github.danieldefrancisco_lr.migrator.analyzer.LegacyModuleAnalyzer.ModuleAnalysisResult;
import io.github.danieldefrancisco_lr.migrator.ui.MigrationWizardDialog;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Handler for the right-click menu action: "Migrate OSGi Module to Client Extension".
 */
public class MigrateModuleHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ISelection selection = HandlerUtil.getCurrentSelection(event);

        if (selection instanceof IStructuredSelection) {
            IStructuredSelection structured = (IStructuredSelection) selection;
            Object element = structured.getFirstElement();
            IResource resource = null;

            if (element instanceof IResource) {
                resource = (IResource) element;
            } else if (element instanceof IAdaptable) {
                resource = ((IAdaptable) element).getAdapter(IResource.class);
            }

            if (resource != null) {
                try {
                    ModuleAnalysisResult analysis = LegacyModuleAnalyzer.analyzeResource(resource);
                    MigrationWizardDialog dialog = new MigrationWizardDialog(
                        HandlerUtil.getActiveShell(event),
                        resource,
                        analysis
                    );
                    dialog.open();
                } catch (Exception e) {
                    MessageDialog.openError(
                        HandlerUtil.getActiveShell(event),
                        "Analysis Error",
                        "Failed to analyze selected module: " + e.getMessage()
                    );
                }
            }
        }

        return null;
    }
}
