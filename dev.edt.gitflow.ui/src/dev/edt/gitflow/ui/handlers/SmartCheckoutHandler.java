package dev.edt.gitflow.ui.handlers;

import org.eclipse.jface.window.Window;
import org.eclipse.jgit.lib.Repository;

import dev.edt.gitflow.core.BranchOperations;

public final class SmartCheckoutHandler
{
    private SmartCheckoutHandler()
    {
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
