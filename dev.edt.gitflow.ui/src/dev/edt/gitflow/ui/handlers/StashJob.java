package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jgit.lib.Repository;

import dev.edt.gitflow.core.RepositorySupport;
import dev.edt.gitflow.core.StashOperations;
import dev.edt.gitflow.core.StashOperations.Outcome;
import dev.edt.gitflow.core.StashOperations.Result;
import dev.edt.gitflow.ui.views.GitFlowView;

final class StashJob
{
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$

    private StashJob()
    {
    }

    static void schedule(ExecutionEvent event, boolean pop)
    {
        Repository repository = Repositories.select(event);
        if (repository == null)
        {
            GitFlowView.publish(Messages.get("selectResource")); //$NON-NLS-1$
            return;
        }
        String[] completionMessage = new String[1];
        Job job = new Job(pop ? Messages.get("popJob") : Messages.get("stashJob")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                if (!RepositorySupport.isSafe(repository))
                {
                    completionMessage[0] = Messages.get("busy"); //$NON-NLS-1$
                    return Status.CANCEL_STATUS;
                }
                Result result = pop ? StashOperations.quickPop(repository, monitor)
                    : StashOperations.quickStash(repository, true, monitor);
                // An apply can change files before reporting a conflict or an error.
                if (result.outcome() != Outcome.NO_CHANGES && result.outcome() != Outcome.NOT_FOUND)
                {
                    try
                    {
                        Repositories.refresh(repository, monitor);
                    }
                    catch (CoreException e)
                    {
                        log(e.getStatus());
                        completionMessage[0] = Messages.get("refreshFailed") + e.getMessage(); //$NON-NLS-1$
                        return e.getStatus();
                    }
                }
                if (result.outcome() == Outcome.ERROR || result.outcome() == Outcome.CONFLICTS)
                    log(new Status(IStatus.ERROR, PLUGIN_ID, result.detail()));
                completionMessage[0] = message(result);
                return result.outcome() == Outcome.ERROR ? new Status(IStatus.ERROR, PLUGIN_ID, message(result)) : Status.OK_STATUS;
            }
        };
        job.setRule(ResourcesPlugin.getWorkspace().getRoot());
        job.addJobChangeListener(new JobChangeAdapter()
        {
            @Override
            public void done(IJobChangeEvent changeEvent)
            {
                if (completionMessage[0] != null)
                    GitFlowView.publish(completionMessage[0]);
            }
        });
        GitFlowView.publish(Messages.get(pop ? "popJob" : "stashJob") //$NON-NLS-1$ //$NON-NLS-2$
            + Messages.get("operationStarted")); //$NON-NLS-1$
        job.schedule();
    }

    private static String message(Result result)
    {
        return switch (result.outcome())
        {
            case CREATED -> Messages.get("created") + result.detail() + Messages.get("createdEnd"); //$NON-NLS-1$ //$NON-NLS-2$
            case NO_CHANGES -> Messages.get("noChanges"); //$NON-NLS-1$
            case APPLIED -> Messages.get("applied"); //$NON-NLS-1$
            case NOT_FOUND -> Messages.get("notFound"); //$NON-NLS-1$
            case CONFLICTS -> Messages.get("conflicts") + result.detail(); //$NON-NLS-1$
            case ERROR -> Messages.get("error") + result.detail(); //$NON-NLS-1$
        };
    }

    private static void log(IStatus status)
    {
        org.eclipse.core.runtime.Platform.getLog(org.eclipse.core.runtime.Platform.getBundle(PLUGIN_ID)).log(status);
    }
}
