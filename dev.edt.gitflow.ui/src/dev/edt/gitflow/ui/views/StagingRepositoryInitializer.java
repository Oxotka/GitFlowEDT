package dev.edt.gitflow.ui.views;

import java.lang.reflect.InvocationTargetException;
import java.util.Comparator;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.model.application.ui.basic.MStackElement;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.advanced.MPerspective;
import org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PerspectiveAdapter;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import dev.edt.gitflow.core.RepositorySupport;
import dev.edt.gitflow.ui.handlers.MergeBranchPickerHook;

public class StagingRepositoryInitializer implements IStartup
{
    private static final String STAGING_VIEW = "org.eclipse.egit.ui.StagingView"; //$NON-NLS-1$
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$
    private static final String GIT_PERSPECTIVE = "org.eclipse.egit.ui.GitRepositoryExploring"; //$NON-NLS-1$

    private final PerspectiveAdapter perspectives = new PerspectiveAdapter()
    {
        @Override
        public void perspectiveOpened(IWorkbenchPage page, IPerspectiveDescriptor perspective)
        {
            arrangeGitViews(page, perspective);
        }

        @Override
        public void perspectiveChanged(IWorkbenchPage page, IPerspectiveDescriptor perspective, String changeId)
        {
            if (IWorkbenchPage.CHANGE_RESET_COMPLETE.equals(changeId))
                arrangeGitViews(page, perspective);
        }
    };

    private void arrangeGitViews(IWorkbenchPage page, IPerspectiveDescriptor perspective)
    {
        if (!GIT_PERSPECTIVE.equals(perspective.getId()))
            return;
        Display.getDefault().asyncExec(() ->
        {
            EModelService models = PlatformUI.getWorkbench().getService(EModelService.class);
            MApplication application = PlatformUI.getWorkbench().getService(MApplication.class);
            if (models == null || application == null)
                return;
            MWindow modelWindow = models.findElements(application, null, MWindow.class, null).stream()
                .filter(window -> window.getWidget() == page.getWorkbenchWindow().getShell())
                .findFirst().orElse(null);
            if (modelWindow == null)
                return;
            for (MPerspective layout : models.findElements(modelWindow, GIT_PERSPECTIVE,
                MPerspective.class, null))
            {
                MStackElement repositories = findTab(models, layout, "org.eclipse.egit.ui.RepositoriesView"); //$NON-NLS-1$
                MStackElement git = findTab(models, layout, "dev.edt.gitflow.ui.view.operations"); //$NON-NLS-1$
                if (repositories != null && git != null
                    && (Object) repositories.getParent() instanceof MPartStack left
                    && left.equals(git.getParent()) && left.getChildren().indexOf(git) > 0)
                {
                    left.getChildren().remove(git);
                    left.getChildren().add(0, git);
                    left.setSelectedElement(git);
                }
            }
        });
    }

    private static MStackElement findTab(EModelService models, MUIElement root, String viewId)
    {
        for (MStackElement tab : models.findElements(root, null, MStackElement.class, null))
        {
            MUIElement view = tab instanceof MPlaceholder placeholder ? placeholder.getRef() : tab;
            if (view != null && viewId.equals(view.getElementId()))
                return tab;
        }
        return null;
    }

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
            MergeBranchPickerHook.install(Display.getDefault());
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
                    window.removePerspectiveListener(perspectives);
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
        window.addPerspectiveListener(perspectives);
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
        if (view instanceof ViewPart viewPart)
            StagingSmartPush.attach(viewPart);
        GitFlowView.stagingMessageAvailable();
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
