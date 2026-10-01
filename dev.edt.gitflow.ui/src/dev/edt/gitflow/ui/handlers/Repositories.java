package dev.edt.gitflow.ui.handlers;

import java.util.Comparator;
import java.util.Set;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.core.commands.ExecutionEvent;

import dev.edt.gitflow.core.RepositorySupport;
import dev.edt.gitflow.ui.views.GitFlowView;

public final class Repositories
{
    private Repositories()
    {
    }

    static Repository select(ExecutionEvent event)
    {
        Repository selected = fromSelection(HandlerUtil.getCurrentSelection(event));
        if (selected == null)
            selected = RepositorySupport.resolveFor(selectedResource(event));
        if (selected != null)
            return selected;

        Set<Repository> repositories = RepositorySupport.allRepositories();
        Repository preferred = GitFlowView.repositoryForCommands();
        if (preferred != null && repositories.stream().anyMatch(
            repository -> repository.getDirectory().equals(preferred.getDirectory())))
            return preferred;
        return repositories.stream()
            .min(Comparator.comparing(repo -> repo.getWorkTree().getAbsolutePath()))
            .orElse(null);
    }

    static IResource selectedResource(ExecutionEvent event)
    {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (selection instanceof IStructuredSelection structured && !structured.isEmpty())
        {
            IResource resource = adapt(structured.getFirstElement());
            if (resource != null)
                return resource;
        }
        IEditorInput input = HandlerUtil.getActiveEditorInput(event);
        return adapt(input);
    }

    public static Repository fromSelection(ISelection selection)
    {
        if (selection instanceof IStructuredSelection structured && !structured.isEmpty())
        {
            Object element = structured.getFirstElement();
            if (element instanceof Repository repository)
                return repository;
            if (element instanceof IAdaptable adaptable)
            {
                Repository repository = adaptable.getAdapter(Repository.class);
                if (repository != null)
                    return repository;
            }
            return RepositorySupport.resolveFor(adapt(element));
        }
        return null;
    }

    public static Repository context(IWorkbenchPage page)
    {
        Repository selected = fromSelection(page.getSelection());
        if (selected != null)
            return selected;
        IEditorPart editor = page.getActiveEditor();
        return editor == null ? null : RepositorySupport.resolveFor(adapt(editor.getEditorInput()));
    }

    private static IResource adapt(Object object)
    {
        if (object instanceof IResource resource)
            return resource;
        return object instanceof IAdaptable adaptable ? adaptable.getAdapter(IResource.class) : null;
    }

    static void refresh(Repository repository, IProgressMonitor monitor) throws CoreException
    {
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (project.isOpen() && repository.equals(RepositorySupport.resolveFor(project)))
                project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        }
    }
}
