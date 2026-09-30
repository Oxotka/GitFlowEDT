package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Команда-приветствие (фаза 0): проверяет, что плагин установлен и виден в EDT.
 * Удаляется в фазе 1.
 */
public class HelloHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        MessageDialog.openInformation(
            HandlerUtil.getActiveShell(event),
            "Git Flow Ops", //$NON-NLS-1$
            "Git Flow Ops установлен. Команды появятся в фазе 1 (см. SPEC.md)."); //$NON-NLS-1$
        return null;
    }
}
