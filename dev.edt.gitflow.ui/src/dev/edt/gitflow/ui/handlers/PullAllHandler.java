package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

public class PullAllHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        OperationJob.scheduleAll(event);
        return null;
    }
}
