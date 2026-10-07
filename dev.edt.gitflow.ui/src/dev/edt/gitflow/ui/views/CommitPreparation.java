package dev.edt.gitflow.ui.views;

import java.util.Set;

import org.eclipse.egit.ui.UIUtils;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.StatusCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.widgets.Shell;

final class CommitPreparation
{
    private CommitPreparation()
    {
    }

    static boolean saveEditors(Repository repository, Shell shell, Set<String> stagedPaths)
    {
        try
        {
            Git git = Git.wrap(repository);
            Status before = stagedPaths.isEmpty() ? null : status(git, stagedPaths);
            if (!UIUtils.saveAllEditors(repository))
                return false;
            if (before == null)
                return true;
            Status after = status(git, stagedPaths);
            for (String path : stagedPaths)
            {
                if (!after.getAdded().contains(path) && !after.getChanged().contains(path)
                    && !after.getRemoved().contains(path))
                    continue;
                if (after.getModified().contains(path) && !before.getModified().contains(path))
                    git.add().addFilepattern(path).call();
                else if (after.getMissing().contains(path) && !before.getMissing().contains(path))
                    git.add().setUpdate(true).addFilepattern(path).call();
            }
            return true;
        }
        catch (GitAPIException e)
        {
            MessageDialog.openError(shell, "Git Flow", //$NON-NLS-1$
                "Не удалось подготовить сохраненные файлы: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    private static Status status(Git git, Set<String> paths) throws GitAPIException
    {
        StatusCommand command = git.status();
        paths.forEach(command::addPath);
        return command.call();
    }
}
