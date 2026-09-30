package dev.edt.gitflow.core;

import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;

public final class RepositorySupport
{
    private RepositorySupport()
    {
    }

    public static Repository resolveFor(IResource resource)
    {
        RepositoryMapping mapping = resource == null ? null : RepositoryMapping.getMapping(resource);
        return mapping == null ? null : mapping.getRepository();
    }

    public static String relativePath(IResource resource)
    {
        RepositoryMapping mapping = resource == null ? null : RepositoryMapping.getMapping(resource);
        return mapping == null ? null : mapping.getRepoRelativePath(resource);
    }

    public static Set<Repository> allRepositories()
    {
        Set<Repository> repositories = new LinkedHashSet<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            if (project.isOpen())
            {
                Repository repository = resolveFor(project);
                if (repository != null)
                    repositories.add(repository);
            }
        }
        return repositories;
    }

    public static boolean isSafe(Repository repository)
    {
        return repository != null && repository.getRepositoryState() == RepositoryState.SAFE;
    }
}
