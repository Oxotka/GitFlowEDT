package dev.edt.gitflow.ui.handlers;

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
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Display;

import dev.edt.gitflow.core.GitLinks;
import dev.edt.gitflow.ui.views.GitFlowView;

public final class OpenLinkHandler
{
    private OpenLinkHandler()
    {
    }

    public static void open(Repository repository, GitLinks.Target target, String path)
    {
        String[] url = new String[1];
        String[] error = new String[1];
        Job job = new Job(Messages.get("openLinkTitle")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    url[0] = GitLinks.link(repository, target, path);
                    return Status.OK_STATUS;
                }
                catch (java.io.IOException | IllegalArgumentException e)
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
                        GitFlowView.publish(error[0]);
                    else if (!Program.launch(url[0]))
                    {
                        Clipboard clipboard = new Clipboard(Display.getDefault());
                        try
                        {
                            clipboard.setContents(new Object[] { url[0] },
                                new Transfer[] { TextTransfer.getInstance() });
                        }
                        finally
                        {
                            clipboard.dispose();
                        }
                        GitFlowView.publish(Messages.get("copiedLink") + " " + url[0]); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    else
                        GitFlowView.publish(Messages.get("openedLink") + " " + url[0]); //$NON-NLS-1$ //$NON-NLS-2$
                });
            }
        });
        job.schedule();
    }
}
