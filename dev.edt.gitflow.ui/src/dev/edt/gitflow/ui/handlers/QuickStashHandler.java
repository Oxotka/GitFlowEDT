package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

public class QuickStashHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        StashJob.schedule(event, false);
        return null;
    }
}
