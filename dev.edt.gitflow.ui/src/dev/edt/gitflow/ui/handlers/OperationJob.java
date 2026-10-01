package dev.edt.gitflow.ui.handlers;

import java.util.function.BiFunction;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import dev.edt.gitflow.core.OperationResult;
import dev.edt.gitflow.core.RepositorySupport;
import dev.edt.gitflow.ui.views.GitFlowView;

public final class OperationJob
{
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$

    private OperationJob()
    {
    }

    static void schedule(ExecutionEvent event, String title,
        BiFunction<Repository, IProgressMonitor, OperationResult> operation)
    {
        schedule(event, title, operation, null);
    }

    static void schedule(ExecutionEvent event, String title,
        BiFunction<Repository, IProgressMonitor, OperationResult> operation,
        BiFunction<Repository, IProgressMonitor, OperationResult> confirmedOperation)
    {
        Repository repository = Repositories.select(event);
        if (repository == null)
        {
            GitFlowView.publish(Messages.get("selectResource")); //$NON-NLS-1$
            return;
        }
        schedule(repository, HandlerUtil.getActiveShell(event), title, operation, confirmedOperation);
    }

    public static void schedule(Repository repository, Shell shell, String title,
        BiFunction<Repository, IProgressMonitor, OperationResult> operation,
        BiFunction<Repository, IProgressMonitor, OperationResult> confirmedOperation)
    {
        if (GitFlowView.isRunning(repository))
        {
            GitFlowView.publish(Messages.get("operationAlreadyRunning")); //$NON-NLS-1$
            return;
        }
        OperationResult[] completion = new OperationResult[1];
        Job job = new Job(title)
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                OperationResult result = operation.apply(repository, monitor);
                completion[0] = result;
                if (result.kind() != OperationResult.Kind.NO_CHANGE
                    && result.kind() != OperationResult.Kind.NEEDS_CONFIRMATION)
                {
                    try
                    {
                        Repositories.refresh(repository, monitor);
                    }
                    catch (CoreException e)
                    {
                        completion[0] = new OperationResult(OperationResult.Kind.ERROR,
                            result.message() + " Обновить проект в EDT не удалось: " + e.getMessage()); //$NON-NLS-1$
                        return e.getStatus();
                    }
                }
                if (!result.succeeded() && result.kind() != OperationResult.Kind.NEEDS_CONFIRMATION)
                    return new Status(IStatus.ERROR, PLUGIN_ID, completion[0].message());
                return Status.OK_STATUS;
            }
        };
        job.setRule(ResourcesPlugin.getWorkspace().getRoot());
        job.addJobChangeListener(new JobChangeAdapter()
        {
            @Override
            public void done(IJobChangeEvent changeEvent)
            {
                GitFlowView.finished(repository);
                if (completion[0] != null)
                    Display.getDefault().asyncExec(() ->
                    {
                        if (completion[0].kind() == OperationResult.Kind.NEEDS_CONFIRMATION
                            && confirmedOperation != null)
                        {
                            if (shell != null && !shell.isDisposed()
                                && MessageDialog.openQuestion(shell, title, completion[0].message()))
                                schedule(repository, shell, title, confirmedOperation, null);
                            else
                                GitFlowView.publish(title + Messages.get("operationCancelled")); //$NON-NLS-1$
                        }
                        else
                            GitFlowView.publish(title + ": " + completion[0].message()); //$NON-NLS-1$
                    });
                else if (!changeEvent.getResult().isOK())
                    GitFlowView.publish(title + ": " + changeEvent.getResult().getMessage()); //$NON-NLS-1$
            }
        });
        GitFlowView.started(repository);
        GitFlowView.useRepository(repository);
        GitFlowView.publish(title + Messages.get("operationStarted")); //$NON-NLS-1$
        job.schedule();
    }

    static void scheduleAll(ExecutionEvent event)
    {
        String[] completion = new String[1];
        Job job = new Job(Messages.get("pullAllJob")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                StringBuilder report = new StringBuilder();
                for (Repository repository : RepositorySupport.allRepositories())
                {
                    monitor.subTask(repository.getWorkTree().getName());
                    OperationResult result = dev.edt.gitflow.core.PullOperations.smartPull(repository, monitor);
                    report.append(repository.getWorkTree().getName()).append(": ") //$NON-NLS-1$
                        .append(result.message()).append('\n');
                    try
                    {
                        Repositories.refresh(repository, monitor);
                    }
                    catch (CoreException e)
                    {
                        report.append("Обновление проекта: ").append(e.getMessage()).append('\n'); //$NON-NLS-1$
                    }
                }
                completion[0] = report.length() == 0 ? Messages.get("noRepositories") : report.toString(); //$NON-NLS-1$
                return Status.OK_STATUS;
            }
        };
        job.setRule(ResourcesPlugin.getWorkspace().getRoot());
        job.addJobChangeListener(new JobChangeAdapter()
        {
            @Override
            public void done(IJobChangeEvent changeEvent)
            {
                if (completion[0] != null)
                    GitFlowView.publish(completion[0]);
            }
        });
        GitFlowView.publish(Messages.get("pullAllJob") + Messages.get("operationStarted")); //$NON-NLS-1$ //$NON-NLS-2$
        job.schedule();
    }
}
