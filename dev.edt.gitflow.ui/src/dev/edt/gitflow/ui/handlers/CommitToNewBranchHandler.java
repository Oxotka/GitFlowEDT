package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.handlers.HandlerUtil;

import dev.edt.gitflow.core.CommitBranchOperations;

public class CommitToNewBranchHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        BranchDialog dialog = new BranchDialog(HandlerUtil.getActiveShell(event), BranchDialog.Mode.COMMIT, null);
        if (dialog.open() == Window.OK)
            OperationJob.schedule(event, Messages.get("commitBranchTitle"), //$NON-NLS-1$
                (repository, monitor) -> CommitBranchOperations.commitToNewBranch(repository, dialog.branch(),
                    dialog.commitMessage(), dialog.stageTracked(), dialog.returnToOriginal(), dialog.push(), monitor),
                (repository, monitor) -> CommitBranchOperations.commitToNewBranch(repository, dialog.branch(),
                    dialog.commitMessage(), true, dialog.returnToOriginal(), dialog.push(), monitor));
        return null;
    }
}
