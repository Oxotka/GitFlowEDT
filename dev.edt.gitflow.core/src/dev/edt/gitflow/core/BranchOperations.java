package dev.edt.gitflow.core;

import java.io.IOException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import dev.edt.gitflow.core.OperationResult.Kind;

public final class BranchOperations
{
    private BranchOperations()
    {
    }

    public static boolean isValidBranchName(String name)
    {
        return name != null && org.eclipse.jgit.api.CreateBranchCommand.isValidBranchName(name);
    }

    public static OperationResult checkout(Repository repository, String target, boolean create,
        IProgressMonitor monitor)
    {
        return checkout(repository, target, create, null, monitor);
    }

    public static OperationResult checkout(Repository repository, String target, boolean create,
        String startPoint, IProgressMonitor monitor)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        if (!isValidBranchName(target))
            return new OperationResult(Kind.ERROR, "Некорректное имя ветки."); //$NON-NLS-1$
        String stashId = null;
        String original;
        monitor.beginTask("Переключение ветки", 3); //$NON-NLS-1$
        try
        {
            Git git = Git.wrap(repository);
            original = repository.getBranch();
            if (target.equals(original))
                return new OperationResult(Kind.NO_CHANGE, "Это уже текущая ветка."); //$NON-NLS-1$
            ObjectId existing = repository.resolve("refs/heads/" + target); //$NON-NLS-1$
            if (create && existing != null)
                return new OperationResult(Kind.ERROR, "Ветка уже существует: " + target); //$NON-NLS-1$
            if (!create && existing == null)
                return new OperationResult(Kind.ERROR, "Ветка не найдена: " + target); //$NON-NLS-1$
            if (startPoint != null && (!create || !startPoint.startsWith("refs/remotes/") //$NON-NLS-1$
                || repository.resolve(startPoint) == null))
                return new OperationResult(Kind.ERROR, "Удалённая ветка не найдена: " + startPoint); //$NON-NLS-1$
            if (!git.status().call().isClean())
            {
                monitor.subTask("Сохранение локальных изменений"); //$NON-NLS-1$
                RevCommit stash = git.stashCreate().setIncludeUntracked(true).call();
                if (stash == null)
                    return new OperationResult(Kind.ERROR, "Не удалось сохранить локальные изменения."); //$NON-NLS-1$
                stashId = stash.getId().name();
            }
            monitor.worked(1);
            try
            {
                monitor.subTask("Переключение на " + target); //$NON-NLS-1$
                var command = git.checkout().setName(target).setCreateBranch(create);
                if (startPoint != null)
                    command.setStartPoint(startPoint).setUpstreamMode(SetupUpstreamMode.TRACK);
                command.call();
            }
            catch (GitAPIException e)
            {
                if (stashId != null && original.equals(repository.getBranch()) && RepositorySupport.isSafe(repository))
                {
                    StashOperations.Result restored = StashOperations.applyAndDrop(repository, stashId);
                    if (restored.outcome() != StashOperations.Outcome.APPLIED)
                        return new OperationResult(Kind.CONFLICT,
                            "Переключение не удалось; исходные изменения остались в стеше: " //$NON-NLS-1$
                                + restored.detail());
                }
                return new OperationResult(Kind.ERROR, "Переключение не удалось: " + e.getMessage()); //$NON-NLS-1$
            }
            monitor.worked(1);
            if (stashId != null)
            {
                monitor.subTask("Восстановление локальных изменений"); //$NON-NLS-1$
                StashOperations.Result restored = StashOperations.applyAndDrop(repository, stashId);
                if (restored.outcome() != StashOperations.Outcome.APPLIED)
                    return new OperationResult(Kind.CONFLICT,
                        "Ветка переключена, но изменения остались в стеше: " + restored.detail()); //$NON-NLS-1$
            }
            monitor.worked(1);
            return new OperationResult(Kind.SUCCESS, "Текущая ветка: " + target); //$NON-NLS-1$
        }
        catch (GitAPIException | IOException e)
        {
            return new OperationResult(Kind.ERROR,
                e.getMessage() + (stashId == null ? "" : " Изменения сохранены в стеше " + stashId)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            monitor.done();
        }
    }

    public static OperationResult undoLastCommit(Repository repository, boolean keepStaged,
        boolean confirmPushed, IProgressMonitor monitor)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        monitor.beginTask("Отмена последнего коммита", 1); //$NON-NLS-1$
        try (RevWalk walk = new RevWalk(repository))
        {
            ObjectId headId = repository.resolve("HEAD"); //$NON-NLS-1$
            if (headId == null)
                return new OperationResult(Kind.ERROR, "В ветке нет коммитов."); //$NON-NLS-1$
            RevCommit head = walk.parseCommit(headId);
            if (head.getParentCount() != 1)
                return new OperationResult(Kind.ERROR,
                    "Корневой или merge-коммит нельзя отменить этой командой."); //$NON-NLS-1$
            if (isPushed(repository, head, walk) && !confirmPushed)
                return new OperationResult(Kind.NEEDS_CONFIRMATION,
                    "Коммит уже отправлен. Отмена изменит историю ветки и для повторной отправки потребуется force push."); //$NON-NLS-1$
            Git.wrap(repository).reset().setMode(keepStaged ? ResetType.SOFT : ResetType.MIXED)
                .setRef(head.getParent(0).getId().name()).call();
            monitor.worked(1);
            return new OperationResult(Kind.SUCCESS,
                "Коммит «" + head.getShortMessage() + "» отменён; изменения " //$NON-NLS-1$ //$NON-NLS-2$
                    + (keepStaged ? "оставлены в индексе." : "возвращены в рабочую область.")); //$NON-NLS-1$ //$NON-NLS-2$
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

    static boolean isPushed(Repository repository, RevCommit head, RevWalk walk) throws IOException
    {
        BranchConfig branch = new BranchConfig(repository.getConfig(), repository.getBranch());
        String tracking = branch.getRemoteTrackingBranch();
        ObjectId remoteId = tracking == null ? null : repository.resolve(tracking);
        return remoteId != null && walk.isMergedInto(head, walk.parseCommit(remoteId));
    }
}
