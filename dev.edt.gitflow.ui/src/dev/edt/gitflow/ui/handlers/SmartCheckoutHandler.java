package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.window.Window;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.ui.handlers.HandlerUtil;

import dev.edt.gitflow.core.BranchOperations;

public class SmartCheckoutHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        Repository repository = Repositories.select(event);
        if (repository != null)
            open(HandlerUtil.getActiveShell(event), repository);
        return null;
    }

    public static void open(org.eclipse.swt.widgets.Shell shell, Repository repository)
    {
        BranchDialog dialog = new BranchDialog(shell, BranchDialog.Mode.CHECKOUT, repository);
        int result = dialog.open();
        if (dialog.edtBranchWizardRequested())
            EdtBranchWizardWorkflow.open(shell, repository);
        else if (result == Window.OK)
            OperationJob.schedule(repository, shell, Messages.get("checkoutTitle"), //$NON-NLS-1$
                (selected, monitor) -> BranchOperations.checkout(selected, dialog.branch(), dialog.createBranch(),
                    dialog.startPoint(), monitor), null);
    }

    public static void openCreateBranchWizard(org.eclipse.swt.widgets.Shell shell, Repository repository)
    {
        EdtBranchWizardWorkflow.open(shell, repository);
    }

    public static String selectReference(org.eclipse.swt.widgets.Shell shell, Repository repository)
    {
        BranchDialog dialog = new BranchDialog(shell, BranchDialog.Mode.COMPARE, repository);
        return dialog.open() == Window.OK ? dialog.selectedRef() : null;
    }
}
