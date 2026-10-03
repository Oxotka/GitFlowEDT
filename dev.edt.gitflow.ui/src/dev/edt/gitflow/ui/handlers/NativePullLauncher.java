package dev.edt.gitflow.ui.handlers;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;

import dev.edt.gitflow.core.RepositorySupport;
import dev.edt.gitflow.ui.views.GitFlowView;

public final class NativePullLauncher
{
    private static final String COMMAND_ID = "org.eclipse.egit.ui.team.Pull"; //$NON-NLS-1$
    private NativePullLauncher()
    {
    }

    public static void start(Repository repository, String title)
    {
        IProject project = projectFor(repository);
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (project == null || window == null)
        {
            GitFlowView.publish(title + ": Не удалось найти проект EDT для запуска штатного получения и слияния."); //$NON-NLS-1$
            return;
        }

        IHandlerService handlers = window.getService(IHandlerService.class);
        ICommandService commands = window.getService(ICommandService.class);
        IEvaluationContext context = handlers.createContextSnapshot(false);
        context.addVariable(ISources.ACTIVE_MENU_SELECTION_NAME, new StructuredSelection(project));
        AtomicReference<Job> pullJob = new AtomicReference<>();
        boolean[] markedRunning = { false };
        String branch;
        try
        {
            branch = repository.getBranch();
        }
        catch (IOException e)
        {
            GitFlowView.publish(title + ": Не удалось определить текущую ветку: " + e.getMessage()); //$NON-NLS-1$
            return;
        }
        IJobManager manager = Job.getJobManager();
        JobChangeAdapter listener = new JobChangeAdapter()
        {
            @Override
            public void scheduled(IJobChangeEvent event)
            {
                Job job = event.getJob();
                if (job.getName().startsWith("Pulling branch ") && job.getName().contains(branch + " - ") //$NON-NLS-1$ //$NON-NLS-2$
                    && pullJob.compareAndSet(null, job))
                {
                    markedRunning[0] = true;
                    GitFlowView.started(repository);
                    GitFlowView.useRepository(repository);
                    GitFlowView.publish(title + ": Запущено штатное получение и слияние EDT. После него проверьте исходящие коммиты."); //$NON-NLS-1$
                }
            }

            @Override
            public void done(IJobChangeEvent event)
            {
                if (event.getJob() != pullJob.get())
                    return;
                manager.removeJobChangeListener(this);
                GitFlowView.finished(repository);
                GitFlowView.publish(title + ": EDT завершила получение и слияние. Отправка не выполнялась."); //$NON-NLS-1$
            }
        };
        manager.addJobChangeListener(listener);
        try
        {
            handlers.executeCommandInContext(
                org.eclipse.core.commands.ParameterizedCommand.generateCommand(
                    commands.getCommand(COMMAND_ID), Map.of()), null, context);
            if (pullJob.get() == null)
            {
                manager.removeJobChangeListener(listener);
                GitFlowView.publish(title + ": EDT не запустила штатное получение и слияние."); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            manager.removeJobChangeListener(listener);
            if (markedRunning[0])
                GitFlowView.finished(repository);
            GitFlowView.publish(title + ": Не удалось запустить штатное получение и слияние EDT: " //$NON-NLS-1$
                + e.getMessage());
        }
    }

    private static IProject projectFor(Repository repository)
    {
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            if (project.isOpen() && repository.equals(RepositorySupport.resolveFor(project)))
                return project;
        return null;
    }
}
