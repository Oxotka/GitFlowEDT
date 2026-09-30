package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

import dev.edt.gitflow.core.PullOperations;

public class SmartPullHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        OperationJob.schedule(event, Messages.get("pullJob"), PullOperations::smartPull); //$NON-NLS-1$
        return null;
    }
}
