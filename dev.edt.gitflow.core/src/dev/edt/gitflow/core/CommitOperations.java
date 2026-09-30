package dev.edt.gitflow.core;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;

import dev.edt.gitflow.core.OperationResult.Kind;

public final class CommitOperations
{
    private static final Pattern TASK_KEY = Pattern.compile("([A-Z]+-\\d+)"); //$NON-NLS-1$
    private static final Set<String> PROTECTED_BRANCHES = Set.of("main", "master", "develop"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private CommitOperations()
    {
    }

    public static String generateMessage(String branch)
    {
        Matcher matcher = TASK_KEY.matcher(branch == null ? "" : branch); //$NON-NLS-1$
        return matcher.find() ? matcher.group(1) + ": " : null; //$NON-NLS-1$
    }

    public static OperationResult safeCommit(Repository repository, String message, boolean stageTracked,
        boolean confirmProtected, IProgressMonitor monitor)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        if (message == null || message.isBlank())
            return new OperationResult(Kind.ERROR, "Введите сообщение коммита."); //$NON-NLS-1$
        if (!TASK_KEY.matcher(message).find())
            return new OperationResult(Kind.ERROR, "В сообщении коммита нужен ключ задачи, например JIRA-1234."); //$NON-NLS-1$
        monitor.beginTask("Безопасный коммит", 2); //$NON-NLS-1$
        try
        {
            Git git = Git.wrap(repository);
            String branch = repository.getBranch();
            if (PROTECTED_BRANCHES.contains(branch) && !confirmProtected)
                return new OperationResult(Kind.NEEDS_CONFIRMATION,
                    "Вы собираетесь сделать коммит в защищённую ветку " + branch + ". Продолжить?"); //$NON-NLS-1$ //$NON-NLS-2$
            Status status = git.status().call();
            if (status.isClean())
                return new OperationResult(Kind.NO_CHANGE, "Изменений для коммита нет."); //$NON-NLS-1$
            if (stageTracked)
                git.add().setUpdate(true).addFilepattern(".").call(); //$NON-NLS-1$
            monitor.worked(1);
            Status staged = git.status().call();
            if (staged.getAdded().isEmpty() && staged.getChanged().isEmpty() && staged.getRemoved().isEmpty())
                return new OperationResult(Kind.ERROR, "Нет подготовленных отслеживаемых изменений."); //$NON-NLS-1$
            git.commit().setMessage(message).call();
            monitor.worked(1);
            return new OperationResult(Kind.SUCCESS, "Коммит создан в ветке " + branch + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (GitAPIException | IOException e)
        {
            return new OperationResult(Kind.ERROR, e.getMessage());
        }
        finally
        {
            monitor.done();
        }
    }
}
