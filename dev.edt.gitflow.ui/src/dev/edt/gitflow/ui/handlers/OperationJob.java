package dev.edt.gitflow.ui.handlers;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.ArrayList;

import org.eclipse.egit.core.internal.job.RuleUtil;
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

import dev.edt.gitflow.core.OperationResult;
import dev.edt.gitflow.ui.views.GitFlowView;

public final class OperationJob
{
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$

    private OperationJob()
    {
    }

    public static void schedule(Repository repository, Shell shell, String title,
        BiFunction<Repository, IProgressMonitor, OperationResult> operation,
        BiFunction<Repository, IProgressMonitor, OperationResult> confirmedOperation)
    {
        schedule(repository, shell, title, operation, confirmedOperation, null);
    }

    public static void schedule(Repository repository, Shell shell, String title,
        BiFunction<Repository, IProgressMonitor, OperationResult> operation,
        BiFunction<Repository, IProgressMonitor, OperationResult> confirmedOperation,
        Consumer<OperationResult> completionAction)
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
                if (result.kind() == OperationResult.Kind.CANCELLED)
                    return Status.CANCEL_STATUS;
                if (!result.succeeded() && result.kind() != OperationResult.Kind.NEEDS_CONFIRMATION
                    && result.kind() != OperationResult.Kind.NEEDS_NATIVE_MERGE
                    && result.kind() != OperationResult.Kind.NEEDS_CHECKOUT_CLEANUP)
                    return new Status(IStatus.ERROR, PLUGIN_ID, completion[0].message());
                return Status.OK_STATUS;
            }
        };
        job.setRule(RuleUtil.getRule(repository));
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
                                    completionAction);
                            return;
                        }
                        if (completion[0].kind() == OperationResult.Kind.NEEDS_CONFIRMATION
                            && confirmedOperation != null)
                        {
                            if (shell != null && !shell.isDisposed()
                                && MessageDialog.openQuestion(shell, title, completion[0].message()))
                                schedule(repository, shell, title, confirmedOperation, null,
                                    completionAction);
                            else
                                GitFlowView.publishStatus(title + Messages.get("operationCancelled"),
                                    Messages.get("operationCancelledShort")); //$NON-NLS-1$
                        }
                        else
                        {
                            if (completionAction != null)
                                completionAction.accept(completion[0]);
                            if (completion[0].kind() == OperationResult.Kind.CANCELLED)
                            {
                                GitFlowView.publishStatus(title + ": " + completion[0].message(), //$NON-NLS-1$
                                    Messages.get("operationCancelledShort")); //$NON-NLS-1$
                                return;
                            }
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
                else if (changeEvent.getResult().getSeverity() == IStatus.CANCEL)
                    GitFlowView.publishStatus(title + Messages.get("operationCancelled"), //$NON-NLS-1$
                        Messages.get("operationCancelledShort")); //$NON-NLS-1$
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

}
