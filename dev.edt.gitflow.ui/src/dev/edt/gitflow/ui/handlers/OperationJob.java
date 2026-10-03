package dev.edt.gitflow.ui.handlers;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.ArrayList;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.ProgressMonitorWrapper;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.egit.ui.internal.UIRepositoryUtils;
import org.eclipse.egit.ui.internal.UIText;
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
        schedule(repository, shell, title, operation, confirmedOperation, true);
    }

    /**
     * @param refreshWorkspace {@code false} для операций, меняющих только индекс
     *            (stage/unstage/commit): рабочая копия не тронута, и широкое
     *            {@code refreshLocal} лишь рассылает лишние события workspace.
     */
    public static void schedule(Repository repository, Shell shell, String title,
        BiFunction<Repository, IProgressMonitor, OperationResult> operation,
        BiFunction<Repository, IProgressMonitor, OperationResult> confirmedOperation,
        boolean refreshWorkspace)
    {
        schedule(repository, shell, title, operation, confirmedOperation, refreshWorkspace, null);
    }

    public static void schedule(Repository repository, Shell shell, String title,
        BiFunction<Repository, IProgressMonitor, OperationResult> operation,
        BiFunction<Repository, IProgressMonitor, OperationResult> confirmedOperation,
        boolean refreshWorkspace, Consumer<OperationResult> completionAction)
    {
        if (GitFlowView.isRunning(repository))
        {
            GitFlowView.publish(Messages.get("operationAlreadyRunning")); //$NON-NLS-1$
            return;
        }
        OperationResult[] completion = new OperationResult[1];
        String[] currentPhase = new String[] { phase(title) };
        Job job = new Job(title)
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                OperationResult result = operation.apply(repository, new ProgressMonitorWrapper(monitor)
                {
                    private int totalWork;
                    private int worked;
                    private int lastPercent = -1;

                    @Override
                    public void beginTask(String name, int totalWork)
                    {
                        this.totalWork = totalWork;
                        worked = 0;
                        lastPercent = -1;
                        super.beginTask(name, totalWork);
                        if (name != null && !name.isBlank())
                            currentPhase[0] = phase(name);
                        GitFlowView.updateProgress(currentPhase[0]);
                    }

                    @Override
                    public void subTask(String name)
                    {
                        super.subTask(name);
                        currentPhase[0] = phase(name);
                        GitFlowView.publishStatus(title + ": " + name, currentPhase[0]); //$NON-NLS-1$
                    }

                    @Override
                    public void worked(int work)
                    {
                        super.worked(work);
                        worked += work;
                        if (totalWork > 0)
                        {
                            int percent = Math.min(100, worked * 100 / totalWork);
                            if (percent != lastPercent)
                            {
                                lastPercent = percent;
                                GitFlowView.updateProgress(currentPhase[0] + " (" + percent + "%)"); //$NON-NLS-1$
                            }
                        }
                    }
                });
                completion[0] = result;
                if (refreshWorkspace && result.workspaceChanged())
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
                if (!result.succeeded() && result.kind() != OperationResult.Kind.NEEDS_CONFIRMATION
                    && result.kind() != OperationResult.Kind.NEEDS_NATIVE_MERGE
                    && result.kind() != OperationResult.Kind.NEEDS_CHECKOUT_CLEANUP)
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
                        if (completion[0].kind() == OperationResult.Kind.NEEDS_CHECKOUT_CLEANUP)
                        {
                            boolean retry = UIRepositoryUtils.showCleanupDialog(repository,
                                new ArrayList<>(completion[0].affectedPaths()),
                                UIText.BranchResultDialog_CheckoutConflictsTitle, shell);
                            if (retry)
                                schedule(repository, shell, title, operation, confirmedOperation,
                                    refreshWorkspace, completionAction);
                            return;
                        }
                        if (completion[0].kind() == OperationResult.Kind.NEEDS_CONFIRMATION
                            && confirmedOperation != null)
                        {
                            if (shell != null && !shell.isDisposed()
                                && MessageDialog.openQuestion(shell, title, completion[0].message()))
                                schedule(repository, shell, title, confirmedOperation, null,
                                    refreshWorkspace, completionAction);
                            else
                                GitFlowView.publishStatus(title + Messages.get("operationCancelled"),
                                    Messages.get("operationCancelledShort")); //$NON-NLS-1$
                        }
                        else
                        {
                            if (completionAction != null)
                                completionAction.accept(completion[0]);
                            if (completion[0].kind() == OperationResult.Kind.NEEDS_NATIVE_MERGE)
                            {
                                NativePullLauncher.start(repository, title);
                                return;
                            }
                            boolean succeeded = completion[0].succeeded();
                            GitFlowView.publishStatus(title + ": " + completion[0].message(), //$NON-NLS-1$
                                succeeded ? Messages.get("allDone") : Messages.get("operationFailedShort"));
                        }
                    });
                else if (!changeEvent.getResult().isOK())
                    GitFlowView.publishStatus(title + ": " + changeEvent.getResult().getMessage(), //$NON-NLS-1$
                        Messages.get("operationFailedShort")); //$NON-NLS-1$
            }
        });
        GitFlowView.started(repository);
        GitFlowView.useRepository(repository);
        GitFlowView.publishStatus(title + Messages.get("operationStarted"), phase(title)); //$NON-NLS-1$
        job.schedule();
    }

    private static String phase(String text)
    {
        String value = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT); //$NON-NLS-1$
        if (value.contains("подготовить все") || value.contains("подготовка всех")) //$NON-NLS-1$ //$NON-NLS-2$
            return "Подготавливаются изменения…"; //$NON-NLS-1$
        if (value.contains("подготов")) return "Подготавливается файл…"; //$NON-NLS-1$
        if (value.contains("отменить все")) return "Отменяем все изменения…"; //$NON-NLS-1$
        if (value.contains("вернуть") || value.contains("убрать из коммита")) return "Возвращаем файл в изменения…"; //$NON-NLS-1$
        if (value.contains("отмен")) return "Отменяем изменения…"; //$NON-NLS-1$
        if (value.contains("переключ")) return "Переключаем ветку…"; //$NON-NLS-1$
        if (value.contains("получ") || value.contains("pull") || value.contains("fetch")) return "Получаем изменения…"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (value.contains("отправ") || value.contains("push")) return "Отправляем изменения…"; //$NON-NLS-1$ //$NON-NLS-2$
        if (value.contains("коммит") || value.contains("зафиксир")) return "Создаём коммит…"; //$NON-NLS-1$ //$NON-NLS-2$
        if (value.contains("спрят") || value.contains("стеш")) return "Сохраняем локальные изменения…"; //$NON-NLS-1$ //$NON-NLS-2$
        if (value.contains("истори")) return "Обновляем историю…"; //$NON-NLS-1$
        return "Выполняется операция…"; //$NON-NLS-1$
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
                        if (result.workspaceChanged())
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
