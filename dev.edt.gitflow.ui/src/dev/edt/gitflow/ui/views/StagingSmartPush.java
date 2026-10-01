package dev.edt.gitflow.ui.views;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.part.ViewPart;

import dev.edt.gitflow.core.OperationResult;
import dev.edt.gitflow.core.PullOperations;
import dev.edt.gitflow.core.RepositorySupport;

final class StagingSmartPush
{
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$
    private static final String HOOKED = PLUGIN_ID + ".smartPushHook"; //$NON-NLS-1$
    private static final String ORIGINAL = PLUGIN_ID + ".nativePushListener"; //$NON-NLS-1$
    private static final String PUSHING = PLUGIN_ID + ".smartPushRunning"; //$NON-NLS-1$
    private static final String REBASE_SECTION = PLUGIN_ID + ".rebaseSection"; //$NON-NLS-1$
    private static final String BUTTON_LABEL = PLUGIN_ID + ".buttonLabel"; //$NON-NLS-1$

    private StagingSmartPush()
    {
    }

    static void attach(ViewPart view)
    {
        try
        {
            Class<?> type = view.getClass();
            Method commit = type.getDeclaredMethod("internalCommit", boolean.class, Runnable.class); //$NON-NLS-1$
            Method enable = type.getDeclaredMethod("enableAllWidgets", boolean.class); //$NON-NLS-1$
            type.getMethod("getCurrentRepository"); //$NON-NLS-1$
            Field field = type.getDeclaredField("commitAndPushButton"); //$NON-NLS-1$
            commit.setAccessible(true);
            enable.setAccessible(true);
            field.setAccessible(true);
            Button button = (Button) field.get(view);
            if (button == null || button.isDisposed() || button.getData(HOOKED) != null)
                return;
            Listener[] original = button.getListeners(SWT.Selection);
            if (original.length == 0)
                return;
            button.setData(ORIGINAL, original);
            for (Listener listener : original)
                button.removeListener(SWT.Selection, listener);
            button.addListener(SWT.Selection, event -> clicked(view, button, commit, enable));
            button.setData(HOOKED, Boolean.TRUE);
            button.setToolTipText("Штатный коммит, затем Smart Push: получение и отправка"); //$NON-NLS-1$
            attachRebaseSection(view, button);
        }
        catch (ReflectiveOperationException | SecurityException e)
        {
            log("Штатная кнопка EGit сохранена: адаптер Smart Push несовместим с этой версией.", e); //$NON-NLS-1$
        }
    }

    private static void attachRebaseSection(ViewPart view, Button button)
    {
        try
        {
            Field field = view.getClass().getDeclaredField("rebaseSection"); //$NON-NLS-1$
            field.setAccessible(true);
            Control section = (Control) field.get(view);
            if (section != null)
            {
                button.setData(REBASE_SECTION, section);
                section.addListener(SWT.Show, event ->
                {
                    if (Boolean.TRUE.equals(button.getData(PUSHING)))
                        Display.getDefault().asyncExec(() -> hideRebaseSection(button));
                });
            }
        }
        catch (ReflectiveOperationException e)
        {
            log("Не удалось скрывать временные кнопки rebase во время Smart Push.", e); //$NON-NLS-1$
        }
    }

    private static void hideRebaseSection(Button button)
    {
        if (button.isDisposed() || !Boolean.TRUE.equals(button.getData(PUSHING))
            || !(button.getData(REBASE_SECTION) instanceof Control section) || section.isDisposed())
            return;
        section.setVisible(false);
        if (section.getLayoutData() instanceof GridData data)
            data.exclude = true;
        section.getParent().layout(true);
    }

    private static void clicked(ViewPart view, Button button, Method commit, Method enable)
    {
        if (Boolean.TRUE.equals(button.getData(PUSHING)))
            return;
        Repository repository = currentRepository(view);
        if (repository == null)
            return;
        try
        {
            if (!hasStagedChanges(repository))
            {
                push(view, button, repository, false, false);
                return;
            }
            ObjectId before = repository.resolve(Constants.HEAD);
            String branch = repository.getBranch();
            enable.invoke(view, false);
            Runnable afterCommit = () ->
            {
                try
                {
                    enable.invoke(view, true);
                    if (Objects.equals(branch, repository.getBranch())
                        && !Objects.equals(before, repository.resolve(Constants.HEAD)))
                        push(view, button, repository, false, true);
                }
                catch (ReflectiveOperationException | IOException e)
                {
                    log("Коммит сохранён локально, но Smart Push не запущен.", e); //$NON-NLS-1$
                }
            };
            if (!Boolean.TRUE.equals(commit.invoke(view, false, afterCommit)))
                enable.invoke(view, true);
        }
        catch (ReflectiveOperationException | GitAPIException | IOException e)
        {
            try
            {
                enable.invoke(view, true);
            }
            catch (ReflectiveOperationException failure)
            {
                log("Не удалось восстановить кнопки индексирования.", failure); //$NON-NLS-1$
            }
            Throwable cause = e instanceof InvocationTargetException invocation
                ? invocation.getCause() : e;
            log("Адаптер кнопки Smart Push отключён.", cause); //$NON-NLS-1$
            restoreNativeButton(button);
            MessageDialog.openError(view.getSite().getShell(), "Git Flow Ops", //$NON-NLS-1$
                "Операция не запущена: " + cause.getMessage()); //$NON-NLS-1$
        }
    }

    private static void restoreNativeButton(Button button)
    {
        if (button.isDisposed() || !(button.getData(ORIGINAL) instanceof Listener[] original))
            return;
        for (Listener listener : button.getListeners(SWT.Selection))
            button.removeListener(SWT.Selection, listener);
        for (Listener listener : original)
            button.addListener(SWT.Selection, listener);
        button.setData(HOOKED, Boolean.FALSE);
    }

    private static Repository currentRepository(ViewPart view)
    {
        try
        {
            return (Repository) view.getClass().getMethod("getCurrentRepository").invoke(view); //$NON-NLS-1$
        }
        catch (ReflectiveOperationException e)
        {
            log("Не удалось определить репозиторий индексирования.", e); //$NON-NLS-1$
            return null;
        }
    }

    private static boolean hasStagedChanges(Repository repository) throws GitAPIException
    {
        var status = Git.wrap(repository).status().call();
        return !status.getAdded().isEmpty() || !status.getChanged().isEmpty()
            || !status.getRemoved().isEmpty();
    }

    private static void push(ViewPart view, Button button, Repository repository,
        boolean confirmed, boolean committed)
    {
        if (!button.isDisposed())
        {
            button.setData(BUTTON_LABEL, button.getText());
            button.setData(PUSHING, Boolean.TRUE);
            button.setText("Smart Push…"); //$NON-NLS-1$
            button.getParent().layout(true);
            if (button.getData(REBASE_SECTION) instanceof Control section && !section.isDisposed())
                section.setRedraw(false);
        }
        hideRebaseSection(button);
        view.getViewSite().getActionBars().getStatusLineManager()
            .setMessage("Smart Push выполняется…"); //$NON-NLS-1$
        Job job = new Job("Smart Push") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                OperationResult result;
                try
                {
                    result = PullOperations.smartPush(repository, confirmed, monitor);
                    if (result.succeeded())
                    {
                        try
                        {
                            refresh(repository, monitor);
                        }
                        catch (CoreException e)
                        {
                            result = new OperationResult(OperationResult.Kind.ERROR,
                                result.message() + " Обновить проект в EDT не удалось: " + e.getMessage()); //$NON-NLS-1$
                        }
                    }
                }
                catch (RuntimeException e)
                {
                    log("Smart Push завершился с ошибкой.", e); //$NON-NLS-1$
                    result = new OperationResult(OperationResult.Kind.ERROR, e.getMessage());
                }
                OperationResult outcome = result;
                Display.getDefault().asyncExec(() -> showResult(view, button, repository,
                    outcome, committed));
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    private static void showResult(ViewPart view, Button button, Repository repository,
        OperationResult result, boolean committed)
    {
        if (!button.isDisposed())
        {
            button.setData(PUSHING, null);
            if (button.getData(REBASE_SECTION) instanceof Control section && !section.isDisposed())
                section.setRedraw(true);
            if (button.getData(BUTTON_LABEL) instanceof String label)
                button.setText(label);
            button.setData(BUTTON_LABEL, null);
            button.getParent().layout(true);
        }
        if (view.getSite().getShell().isDisposed())
            return;
        if (result.kind() != OperationResult.Kind.NEEDS_CONFIRMATION
            && !button.isDisposed() && repository.equals(currentRepository(view)))
            try
            {
                view.getClass().getMethod("reload", Repository.class).invoke(view, repository); //$NON-NLS-1$
            }
            catch (ReflectiveOperationException e)
            {
                log("Не удалось обновить кнопки индексирования после Smart Push.", e); //$NON-NLS-1$
            }
        if (result.kind() == OperationResult.Kind.NEEDS_CONFIRMATION)
        {
            if (MessageDialog.openQuestion(view.getSite().getShell(), "Git Flow Ops", result.message())) //$NON-NLS-1$
                push(view, button, repository, true, committed);
        }
        else if (result.succeeded())
            view.getViewSite().getActionBars().getStatusLineManager()
                .setMessage((committed ? "Коммит создан. " : "") + result.message()); //$NON-NLS-1$ //$NON-NLS-2$
        else
            MessageDialog.openError(view.getSite().getShell(), "Git Flow Ops", //$NON-NLS-1$
                (committed ? "Коммит сохранён локально. " : "") + result.message()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void refresh(Repository repository, IProgressMonitor monitor) throws CoreException
    {
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            if (project.isOpen() && repository.equals(RepositorySupport.resolveFor(project)))
                project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
    }

    private static void log(String message, Throwable error)
    {
        Platform.getLog(Platform.getBundle(PLUGIN_ID))
            .log(new Status(IStatus.WARNING, PLUGIN_ID, message, error));
    }
}
