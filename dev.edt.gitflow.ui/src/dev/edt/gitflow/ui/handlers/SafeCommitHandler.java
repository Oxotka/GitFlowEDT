package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.handlers.HandlerUtil;

import dev.edt.gitflow.core.CommitOperations;

public class SafeCommitHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        CommitDialog dialog = new CommitDialog(HandlerUtil.getActiveShell(event));
        if (dialog.open() == Window.OK)
            OperationJob.schedule(event, Messages.get("safeCommitTitle"), //$NON-NLS-1$
                (repository, monitor) -> CommitOperations.safeCommit(repository, dialog.message(),
                    dialog.stageTracked(), false, monitor),
                (repository, monitor) -> CommitOperations.safeCommit(repository, dialog.message(),
                    dialog.stageTracked(), true, monitor));
        return null;
    }
}
