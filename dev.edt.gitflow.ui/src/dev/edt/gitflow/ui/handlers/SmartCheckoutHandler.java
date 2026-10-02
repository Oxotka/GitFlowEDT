package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.PlatformUI;

import dev.edt.gitflow.core.BranchOperations;

public class SmartCheckoutHandler extends AbstractHandler
{
    private static final String EDT_CREATE_BRANCH_COMMAND = "com._1c.g5.v8.dt.team.ui.git.command.createBranch"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event)
    {
        Repository repository = Repositories.select(event);
        if (repository == null)
            return null;
        open(HandlerUtil.getActiveShell(event), repository);
        return null;
    }

    public static void open(org.eclipse.swt.widgets.Shell shell, Repository repository)
    {
        BranchDialog dialog = new BranchDialog(shell, BranchDialog.Mode.CHECKOUT, repository);
        int result = dialog.open();
        if (dialog.edtBranchWizardRequested())
            openEdtBranchWizard(shell, repository);
        else if (result == Window.OK)
            OperationJob.schedule(repository, shell, Messages.get("checkoutTitle"), //$NON-NLS-1$
                (selected, monitor) -> BranchOperations.checkout(selected, dialog.branch(), dialog.createBranch(),
                    dialog.startPoint(), monitor), null);
    }

    private static void openEdtBranchWizard(org.eclipse.swt.widgets.Shell shell, Repository repository)
    {
        IProject project = null;
        for (IProject candidate : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            Repository mapped = candidate.isOpen() ? dev.edt.gitflow.core.RepositorySupport.resolveFor(candidate) : null;
            if (mapped != null && mapped.getDirectory().equals(repository.getDirectory()))
            {
                project = candidate;
                break;
            }
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPart part = window == null || window.getActivePage() == null
            ? null : window.getActivePage().getActivePart();
        ISelectionProvider selectionProvider = part == null ? null : part.getSite().getSelectionProvider();
        if (project == null || selectionProvider == null)
        {
            MessageDialog.openError(shell, Messages.get("title"), //$NON-NLS-1$
                Messages.get(project == null ? "branchWizardProjectMissing" : "branchWizardSelectionMissing")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        var previousSelection = selectionProvider.getSelection();
        try
        {
            selectionProvider.setSelection(new StructuredSelection(project));
            window.getService(IHandlerService.class).executeCommand(EDT_CREATE_BRANCH_COMMAND, new Event());
        }
        catch (Exception e)
        {
            MessageDialog.openError(shell, Messages.get("title"), //$NON-NLS-1$
                Messages.get("branchWizardFailed") + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            selectionProvider.setSelection(previousSelection);
        }
    }
}
