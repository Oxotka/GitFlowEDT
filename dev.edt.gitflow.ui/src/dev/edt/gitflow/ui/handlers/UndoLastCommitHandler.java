package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import dev.edt.gitflow.core.BranchOperations;

public class UndoLastCommitHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        MessageDialog mode = new MessageDialog(HandlerUtil.getActiveShell(event),
            Messages.get("undoTitle"), null, Messages.get("undoMode"), //$NON-NLS-1$ //$NON-NLS-2$
            MessageDialog.QUESTION, new String[] { Messages.get("keepStaged"), //$NON-NLS-1$
                Messages.get("unstage"), Messages.get("cancel") }, 0); //$NON-NLS-1$ //$NON-NLS-2$
        int choice = mode.open();
        if (choice == 0 || choice == 1)
        {
            boolean staged = choice == 0;
            OperationJob.schedule(event, Messages.get("undoTitle"), //$NON-NLS-1$
                (repository, monitor) -> BranchOperations.undoLastCommit(repository, staged, false, monitor),
                (repository, monitor) -> BranchOperations.undoLastCommit(repository, staged, true, monitor));
        }
        return null;
    }
}
