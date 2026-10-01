package dev.edt.gitflow.ui.views;

import java.lang.reflect.InvocationTargetException;
import java.util.Comparator;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import dev.edt.gitflow.core.RepositorySupport;

public class StagingRepositoryInitializer implements IStartup
{
    private static final String STAGING_VIEW = "org.eclipse.egit.ui.StagingView"; //$NON-NLS-1$
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$

    private final IPartListener2 parts = new IPartListener2()
    {
        @Override
        public void partOpened(IWorkbenchPartReference part)
        {
            initializeLater(part);
        }

        @Override
        public void partVisible(IWorkbenchPartReference part)
        {
            initializeLater(part);
        }
    };

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                watch(window);
            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override
                public void windowOpened(IWorkbenchWindow window)
                {
                    watch(window);
                }

                @Override
                public void windowClosed(IWorkbenchWindow window)
                {
                    window.getPartService().removePartListener(parts);
                }

                @Override
                public void windowActivated(IWorkbenchWindow window)
                {
                }

                @Override
                public void windowDeactivated(IWorkbenchWindow window)
                {
                }
            });
        });
    }

    private void watch(IWorkbenchWindow window)
    {
        window.getPartService().addPartListener(parts);
        for (var page : window.getPages())
            for (var view : page.getViewReferences())
                initializeLater(view);
    }

    private void initializeLater(IWorkbenchPartReference part)
    {
        if (STAGING_VIEW.equals(part.getId()))
            Display.getDefault().asyncExec(() -> initialize(part));
    }

    private void initialize(IWorkbenchPartReference part)
    {
        Object view = part.getPart(false);
        if (view == null)
            return;
        try
        {
            if (view.getClass().getMethod("getCurrentRepository").invoke(view) != null) //$NON-NLS-1$
                return;
            Repository repository = RepositorySupport.allRepositories().stream()
                .min(Comparator.comparing(value -> value.getDirectory().getAbsolutePath()))
                .orElse(null);
            if (repository != null)
                view.getClass().getMethod("reload", Repository.class).invoke(view, repository); //$NON-NLS-1$
        }
        catch (ReflectiveOperationException e)
        {
            Throwable cause = e instanceof InvocationTargetException invocation
                ? invocation.getCause() : e;
            Platform.getLog(Platform.getBundle(PLUGIN_ID)).log(new Status(IStatus.WARNING,
                PLUGIN_ID, "Не удалось выбрать репозиторий в Git Staging.", cause)); //$NON-NLS-1$
        }
    }
}
