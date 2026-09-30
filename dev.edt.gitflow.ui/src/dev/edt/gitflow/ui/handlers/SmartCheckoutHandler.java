package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.handlers.HandlerUtil;

import dev.edt.gitflow.core.BranchOperations;

public class SmartCheckoutHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        BranchDialog dialog = new BranchDialog(HandlerUtil.getActiveShell(event), BranchDialog.Mode.CHECKOUT);
        if (dialog.open() == Window.OK)
            OperationJob.schedule(event, Messages.get("checkoutTitle"), //$NON-NLS-1$
                (repository, monitor) -> BranchOperations.checkout(repository, dialog.branch(), dialog.createBranch(), monitor));
        return null;
    }
}
