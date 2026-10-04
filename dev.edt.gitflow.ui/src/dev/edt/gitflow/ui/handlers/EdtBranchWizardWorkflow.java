package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.egit.core.internal.job.RuleUtil;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.dialogs.MessageDialog;

import dev.edt.gitflow.core.RepositorySupport;
import dev.edt.gitflow.core.StashOperations;
import dev.edt.gitflow.core.StashOperations.Outcome;
import dev.edt.gitflow.core.StashOperations.Result;
import dev.edt.gitflow.ui.views.GitFlowView;

final class EdtBranchWizardWorkflow
{
    private static final String EDT_CREATE_BRANCH_COMMAND =
        "com._1c.g5.v8.dt.team.ui.git.command.createBranch"; //$NON-NLS-1$
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$

    private final Repository repository;
    private final IProject project;
    private final IWorkbenchWindow window;
    private final ISelectionProvider selectionProvider;
    private final ISelection previousSelection;
    private Result stashResult;
    private Result restoreResult;

    private EdtBranchWizardWorkflow(Repository repository, IProject project,
        IWorkbenchWindow window, ISelectionProvider selectionProvider)
    {
        this.repository = repository;
        this.project = project;
        this.window = window;
        this.selectionProvider = selectionProvider;
        previousSelection = selectionProvider.getSelection();
    }

    static void open(Shell shell, Repository repository)
    {
        IProject project = null;
        for (IProject candidate : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            Repository mapped = candidate.isOpen() ? RepositorySupport.resolveFor(candidate) : null;
            if (mapped != null && mapped.getDirectory().equals(repository.getDirectory()))
            {
                project = candidate;
                break;
            }
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        IWorkbenchPart part = window == null || window.getActivePage() == null
            ? null : window.getActivePage().getActivePart();
        ISelectionProvider selectionProvider = part == null ? null : part.getSite().getSelectionProvider();
        if (project == null || selectionProvider == null)
        {
            MessageDialog.openError(shell, Messages.get("title"), //$NON-NLS-1$
                Messages.get(project == null ? "branchWizardProjectMissing" : "branchWizardSelectionMissing")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (GitFlowView.isRunning(repository))
        {
            GitFlowView.publish(Messages.get("operationAlreadyRunning")); //$NON-NLS-1$
            return;
        }
        new EdtBranchWizardWorkflow(repository, project, window, selectionProvider).start();
    }

    private void start()
    {
        GitFlowView.started(repository);
        GitFlowView.useRepository(repository);
        GitFlowView.publish(Messages.get("branchWizardPreparing") + Messages.get("operationStarted")); //$NON-NLS-1$
        Job job = new Job(Messages.get("branchWizardPreparing")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                if (!RepositorySupport.isSafe(repository))
                {
                    stashResult = new Result(Outcome.ERROR, Messages.get("busy")); //$NON-NLS-1$
                    return new Status(IStatus.ERROR, PLUGIN_ID, stashResult.detail());
                }
                stashResult = StashOperations.quickStash(repository, true, monitor);
                if (stashResult.outcome() == Outcome.ERROR)
                    return new Status(IStatus.ERROR, PLUGIN_ID, stashResult.detail());
                return Status.OK_STATUS;
            }
        };
        job.setRule(RuleUtil.getRule(repository));
        job.addJobChangeListener(new JobChangeAdapter()
        {
            @Override
            public void done(IJobChangeEvent event)
            {
                Display.getDefault().asyncExec(() ->
                {
                    if (!event.getResult().isOK() || stashResult == null
                        || stashResult.outcome() == Outcome.ERROR)
                    {
                        if (stashId() == null)
                            finish(stashResult == null ? event.getResult().getMessage() : stashResult.detail());
                        else
                            restoreStash(stashResult.detail());
                        return;
                    }
                    String wizardError = openWizard();
                    if (stashId() == null)
                        finish(wizardError == null ? Messages.get("branchWizardDone") : wizardError); //$NON-NLS-1$
                    else
                        restoreStash(wizardError);
                });
            }
        });
        job.schedule();
    }

    private String openWizard()
    {
        try
        {
            selectionProvider.setSelection(new StructuredSelection(project));
            window.getService(IHandlerService.class).executeCommand(EDT_CREATE_BRANCH_COMMAND, new Event());
            return null;
        }
        catch (Exception e)
        {
            return Messages.get("branchWizardFailed") + " " + e.getMessage(); //$NON-NLS-1$
        }
        finally
        {
            selectionProvider.setSelection(previousSelection);
        }
    }

    private String stashId()
    {
        return stashResult == null ? null : stashResult.stashId();
    }

    private void restoreStash(String wizardMessage)
    {
        String stashId = stashId();
        Job job = new Job(Messages.get("branchWizardRestoring")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                restoreResult = StashOperations.applyAndDrop(repository, stashId);
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.setRule(RuleUtil.getRule(repository));
        job.addJobChangeListener(new JobChangeAdapter()
        {
            @Override
            public void done(IJobChangeEvent event)
            {
                Display.getDefault().asyncExec(() ->
                {
                    String message = wizardMessage;
                    if (restoreResult != null && restoreResult.outcome() == Outcome.APPLIED)
                        message = append(message, Messages.get("branchWizardRestored")); //$NON-NLS-1$
                    else if (restoreResult != null)
                        message = append(message, Messages.get("branchWizardRestoreFailed") //$NON-NLS-1$
                            + " " + restoreResult.detail() + " [" + stashId + "]"); //$NON-NLS-1$ //$NON-NLS-2$
                    if (message == null)
                        message = Messages.get("branchWizardRestored"); //$NON-NLS-1$
                    if (!event.getResult().isOK())
                        message = append(message, event.getResult().getMessage());
                    finish(message);
                });
            }
        });
        job.schedule();
    }

    private void finish(String message)
    {
        GitFlowView.finished(repository);
        if (message != null && !message.isBlank())
            GitFlowView.publish(message);
    }

    private static String append(String first, String second)
    {
        return first == null ? second : first + " " + second; //$NON-NLS-1$
    }
}
