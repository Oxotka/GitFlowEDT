package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;

import dev.edt.gitflow.core.CommitOperations;
import dev.edt.gitflow.ui.views.GitFlowView;

public class GenerateMessageHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event)
    {
        Repository repository = Repositories.select(event);
        if (repository == null)
        {
            GitFlowView.publish(Messages.get("selectResource")); //$NON-NLS-1$
            return null;
        }
        String[] generated = new String[1];
        String[] error = new String[1];
        Job job = new Job(Messages.get("generateMessageTitle")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    generated[0] = CommitOperations.generateMessage(repository.getBranch());
                    return Status.OK_STATUS;
                }
                catch (java.io.IOException e)
                {
                    error[0] = e.getMessage();
                    return new Status(IStatus.ERROR, "dev.edt.gitflow.ui", e.getMessage(), e); //$NON-NLS-1$
                }
            }
        };
        job.addJobChangeListener(new JobChangeAdapter()
        {
            @Override
            public void done(IJobChangeEvent changeEvent)
            {
                Display.getDefault().asyncExec(() ->
                {
                    if (error[0] != null)
                    {
                        GitFlowView.publish(error[0]);
                        return;
                    }
                    if (generated[0] == null)
                    {
                        GitFlowView.publish(Messages.get("noTaskKey")); //$NON-NLS-1$
                        return;
                    }
                    Clipboard clipboard = new Clipboard(Display.getDefault());
                    try
                    {
                        clipboard.setContents(new Object[] { generated[0] },
                            new Transfer[] { TextTransfer.getInstance() });
                    }
                    finally
                    {
                        clipboard.dispose();
                    }
                    GitFlowView.publish(Messages.get("copiedMessage") + " " + generated[0]); //$NON-NLS-1$ //$NON-NLS-2$
                });
            }
        });
        job.schedule();
        return null;
    }
}
