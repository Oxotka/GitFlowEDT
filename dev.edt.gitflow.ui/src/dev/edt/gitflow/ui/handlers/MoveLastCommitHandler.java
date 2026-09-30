package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.handlers.HandlerUtil;

import dev.edt.gitflow.core.CommitBranchOperations;

public class MoveLastCommitHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        BranchDialog dialog = new BranchDialog(HandlerUtil.getActiveShell(event), BranchDialog.Mode.MOVE);
        if (dialog.open() == Window.OK)
            OperationJob.schedule(event, Messages.get("moveTitle"), //$NON-NLS-1$
                (repository, monitor) -> CommitBranchOperations.moveLastCommit(repository, dialog.branch(),
                    dialog.createBranch(), dialog.returnToOriginal(), false, monitor),
                (repository, monitor) -> CommitBranchOperations.moveLastCommit(repository, dialog.branch(),
                    dialog.createBranch(), dialog.returnToOriginal(), true, monitor));
        return null;
    }
}
