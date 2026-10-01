package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

import dev.edt.gitflow.ui.views.GitFlowView;

public class OpenGitViewHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        try
        {
            HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage().showView(GitFlowView.ID);
            return null;
        }
        catch (PartInitException e)
        {
            throw new ExecutionException("Не удалось открыть Git-панель.", e); //$NON-NLS-1$
        }
    }
}
