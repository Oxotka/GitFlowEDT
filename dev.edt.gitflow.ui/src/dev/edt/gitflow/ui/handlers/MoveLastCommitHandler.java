package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.window.Window;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.ui.handlers.HandlerUtil;

import dev.edt.gitflow.core.CommitBranchOperations;

public class MoveLastCommitHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        Repository repository = Repositories.select(event);
        if (repository == null)
            return null;
        BranchDialog dialog = new BranchDialog(HandlerUtil.getActiveShell(event), BranchDialog.Mode.MOVE, repository);
        if (dialog.open() == Window.OK)
            OperationJob.schedule(repository, HandlerUtil.getActiveShell(event), Messages.get("moveTitle"), //$NON-NLS-1$
                (selected, monitor) -> CommitBranchOperations.moveLastCommit(selected, dialog.branch(),
                    dialog.createBranch(), dialog.returnToOriginal(), false, monitor),
                (selected, monitor) -> CommitBranchOperations.moveLastCommit(selected, dialog.branch(),
                    dialog.createBranch(), dialog.returnToOriginal(), true, monitor));
        return null;
    }
}
