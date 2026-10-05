package dev.edt.gitflow.core;

import java.io.IOException;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;

import dev.edt.gitflow.core.OperationResult.Kind;

public final class CommitOperations
{
    private static final Set<String> PROTECTED_BRANCHES = Set.of("main", "master", "develop"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private CommitOperations()
    {
    }

    public static OperationResult safeCommit(Repository repository, String message, boolean stageTracked,
        boolean confirmProtected, IProgressMonitor monitor)
    {
        RepositoryState state = repository == null ? null : repository.getRepositoryState();
        boolean mergeReady = state == RepositoryState.MERGING_RESOLVED;
        if (state != RepositoryState.SAFE && !mergeReady)
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        if (message == null || message.isBlank())
            return new OperationResult(Kind.ERROR, "Введите сообщение коммита."); //$NON-NLS-1$
        monitor.beginTask("Безопасный коммит", 2); //$NON-NLS-1$
        try
        {
            if (monitor.isCanceled())
                return new OperationResult(Kind.CANCELLED, "Коммит отменён."); //$NON-NLS-1$
            Git git = Git.wrap(repository);
            String branch = repository.getBranch();
            if (PROTECTED_BRANCHES.contains(branch) && !confirmProtected)
                return new OperationResult(Kind.NEEDS_CONFIRMATION,
                    "Создать коммит в защищённой ветке " + branch + "?"); //$NON-NLS-1$ //$NON-NLS-2$
            Status status = git.status().call();
            if (status.isClean() && !mergeReady)
                return new OperationResult(Kind.NO_CHANGE, "Изменений для коммита нет."); //$NON-NLS-1$
            if (stageTracked)
                git.add().setUpdate(true).addFilepattern(".").call(); //$NON-NLS-1$
            monitor.worked(1);
            if (monitor.isCanceled())
                return new OperationResult(Kind.CANCELLED, "Коммит отменён."); //$NON-NLS-1$
            Status staged = git.status().call();
            if (!mergeReady && staged.getAdded().isEmpty() && staged.getChanged().isEmpty()
                && staged.getRemoved().isEmpty())
                return new OperationResult(Kind.ERROR, "Нет подготовленных отслеживаемых изменений."); //$NON-NLS-1$
            if (monitor.isCanceled())
                return new OperationResult(Kind.CANCELLED, "Коммит отменён."); //$NON-NLS-1$
            git.commit().setMessage(message).call();
            monitor.worked(1);
            return new OperationResult(Kind.SUCCESS, "Коммит создан в ветке " + branch + ".", false, true); //$NON-NLS-1$ //$NON-NLS-2$
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

    public static OperationResult commitAndPush(Repository repository, String message,
        boolean stageTracked, boolean send, boolean confirmed, IProgressMonitor monitor)
    {
        try
        {
            String branch = repository.getBranch();
            org.eclipse.jgit.lib.BranchConfig config = new org.eclipse.jgit.lib.BranchConfig(
                repository.getConfig(), branch);
            boolean hasRemote = config.getRemoteTrackingBranch() != null
                || repository.getConfig().getString("remote", "origin", "url") != null; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (!confirmed && PROTECTED_BRANCHES.contains(branch))
                return new OperationResult(Kind.NEEDS_CONFIRMATION,
                    "Создать коммит в защищённой ветке " + branch + "?" //$NON-NLS-1$ //$NON-NLS-2$
                        + (send && hasRemote ? " После создания коммит будет отправлен на сервер." : "")); //$NON-NLS-1$ //$NON-NLS-2$
            if (!confirmed && send && hasRemote && config.getRemoteTrackingBranch() == null)
                return new OperationResult(Kind.NEEDS_CONFIRMATION,
                    "После коммита связать ветку с origin/" + branch + " и отправить изменения?"); //$NON-NLS-1$ //$NON-NLS-2$
            OperationResult committed = safeCommit(repository, message, stageTracked, true, monitor);
            if (!committed.succeeded() || committed.kind() == Kind.NO_CHANGE)
                return committed;
            if (!send || !hasRemote)
                return new OperationResult(Kind.SUCCESS,
                    committed.message() + (send ? " Коммит сохранён локально: remote не настроен." //$NON-NLS-1$
                        : " Коммит сохранён локально: отправка отключена."), //$NON-NLS-1$
                    false, true);
            if (monitor.isCanceled())
                return new OperationResult(Kind.CANCELLED,
                    "Коммит создан локально. Отправка отменена.", false, true); //$NON-NLS-1$
            OperationResult pushed = PullOperations.smartPush(repository, true, monitor);
            if (pushed.kind() == Kind.CANCELLED)
                return new OperationResult(Kind.CANCELLED,
                    "Коммит создан локально. " + pushed.message(), pushed.workspaceChanged(), true); //$NON-NLS-1$
            if (pushed.kind() == Kind.NEEDS_NATIVE_MERGE)
                return new OperationResult(Kind.NEEDS_NATIVE_MERGE,
                    committed.message() + " " + pushed.message() + " Коммит сохранён локально; после проверки " //$NON-NLS-1$
                        + "отправьте ветку отдельно.", //$NON-NLS-1$
                    pushed.workspaceChanged(), true);
            return pushed.succeeded()
                ? new OperationResult(Kind.SUCCESS, committed.message() + " " + pushed.message(), //$NON-NLS-1$
                    pushed.workspaceChanged(), true)
                : new OperationResult(Kind.ERROR,
                    committed.message() + " Коммит остался локально. Отправка не завершена: " //$NON-NLS-1$
                        + pushed.message(), pushed.workspaceChanged(), true);
        }
        catch (IOException e)
        {
            return new OperationResult(Kind.ERROR, e.getMessage());
        }
    }
}
