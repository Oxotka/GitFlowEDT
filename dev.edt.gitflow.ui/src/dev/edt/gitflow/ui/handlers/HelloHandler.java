package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

/** Shows that Git Flow is installed in EDT. */
public class HelloHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        MessageDialog.openInformation(
            HandlerUtil.getActiveShell(event),
            Messages.get("title"), //$NON-NLS-1$
            Messages.get("hello")); //$NON-NLS-1$
        return null;
    }
}
