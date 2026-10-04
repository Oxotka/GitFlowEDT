package dev.edt.gitflow.ui.handlers;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;

import dev.edt.gitflow.core.RepositorySupport;

public final class Repositories
{
    private Repositories()
    {
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
}
