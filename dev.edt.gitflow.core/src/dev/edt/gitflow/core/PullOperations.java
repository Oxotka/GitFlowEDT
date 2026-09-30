package dev.edt.gitflow.core;

import java.io.IOException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;

import dev.edt.gitflow.core.OperationResult.Kind;

public final class PullOperations
{
    private PullOperations()
    {
    }

    public static OperationResult smartPull(Repository repository, IProgressMonitor monitor)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        String stashId = null;
        monitor.beginTask("Умное получение", 4); //$NON-NLS-1$
        try
        {
            Git git = Git.wrap(repository); // EGit owns this repository.
            BranchConfig branch = new BranchConfig(repository.getConfig(), repository.getBranch());
            String remote = branch.getRemote();
            String tracking = branch.getRemoteTrackingBranch();
            if (remote == null || tracking == null)
                return new OperationResult(Kind.ERROR, "Для текущей ветки не настроен upstream."); //$NON-NLS-1$

            ObjectId previousTracking = repository.resolve(tracking);
            monitor.subTask("Получение из " + remote); //$NON-NLS-1$
            git.fetch().setRemote(remote).call();
            monitor.worked(1);
            ObjectId upstream = repository.resolve(tracking);
            if (upstream == null)
                return new OperationResult(Kind.ERROR, "После fetch не найдена ветка " + tracking); //$NON-NLS-1$
            int fetched = previousTracking == null ? 0 : countRange(git, previousTracking, upstream);

            ObjectId head = repository.resolve("HEAD"); //$NON-NLS-1$
            try (RevWalk walk = new RevWalk(repository))
            {
                if (head != null && walk.isMergedInto(walk.parseCommit(upstream), walk.parseCommit(head)))
                    return new OperationResult(Kind.NO_CHANGE,
                        "Удалённая ветка актуальна; локальные изменения не затронуты."); //$NON-NLS-1$
            }

            // Fetch happens before stashing, so a network error leaves the worktree untouched.
            if (!git.status().call().isClean())
            {
                monitor.subTask("Сохранение локальных изменений"); //$NON-NLS-1$
                RevCommit stash = git.stashCreate().setIncludeUntracked(true).call();
                if (stash == null)
                    return new OperationResult(Kind.ERROR, "Не удалось сохранить локальные изменения."); //$NON-NLS-1$
                stashId = stash.getId().name();
            }
            monitor.worked(1);
            monitor.subTask("Обновление ветки"); //$NON-NLS-1$
            RebaseResult rebase = git.rebase().setUpstream(upstream).call();
            monitor.worked(1);
            if (!rebase.getStatus().isSuccessful())
                return new OperationResult(Kind.CONFLICT,
                    "Rebase остановлен: " + rebase.getStatus() + stashNote(stashId)); //$NON-NLS-1$

            if (stashId != null)
            {
                monitor.subTask("Восстановление локальных изменений"); //$NON-NLS-1$
                StashOperations.Result restored = StashOperations.applyAndDrop(repository, stashId);
                if (restored.outcome() != StashOperations.Outcome.APPLIED)
                    return new OperationResult(Kind.CONFLICT,
                        "Обновление прошло, но локальные изменения не восстановлены: " //$NON-NLS-1$
                            + restored.detail() + " Стеш сохранён."); //$NON-NLS-1$
            }
            monitor.worked(1);
            return fetched == 0
                ? new OperationResult(Kind.NO_CHANGE, "Ветка актуальна, локальные изменения восстановлены.") //$NON-NLS-1$
                : new OperationResult(Kind.SUCCESS, "Получено коммитов: " + fetched //$NON-NLS-1$
                    + ". Локальные изменения восстановлены."); //$NON-NLS-1$
        }
        catch (GitAPIException | IOException e)
        {
            return new OperationResult(Kind.ERROR, e.getMessage() + stashNote(stashId));
        }
        finally
        {
            monitor.done();
        }
    }

    public static OperationResult smartPush(Repository repository, IProgressMonitor monitor)
    {
        OperationResult pulled = smartPull(repository, monitor);
        if (!pulled.succeeded())
            return pulled;
        try
        {
            Git git = Git.wrap(repository);
            String name = repository.getBranch();
            BranchConfig branch = new BranchConfig(repository.getConfig(), name);
            String remote = branch.getRemote();
            String merge = branch.getMerge();
            if (remote == null || merge == null)
                return new OperationResult(Kind.ERROR, "Для отправки не настроен upstream."); //$NON-NLS-1$
            RefSpec spec = new RefSpec("refs/heads/" + name + ":" + merge); //$NON-NLS-1$ //$NON-NLS-2$
            int updated = 0;
            for (PushResult push : git.push().setRemote(remote).setRefSpecs(spec).call())
            {
                for (RemoteRefUpdate ref : push.getRemoteUpdates())
                {
                    if (ref.getStatus() == RemoteRefUpdate.Status.OK)
                        updated++;
                    else if (ref.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE)
                        return new OperationResult(Kind.ERROR,
                            "Получение прошло, но push отклонён: " + ref.getStatus() //$NON-NLS-1$
                                + ". Повторите синхронизацию."); //$NON-NLS-1$
                }
            }
            return updated == 0
                ? new OperationResult(Kind.NO_CHANGE, "Ветка уже синхронизирована.") //$NON-NLS-1$
                : new OperationResult(Kind.SUCCESS, pulled.message() + " Отправлена ветка " + name); //$NON-NLS-1$
        }
        catch (GitAPIException | IOException e)
        {
            return new OperationResult(Kind.ERROR, "Получение прошло, но отправка не удалась: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static int countRange(Git git, ObjectId oldId, ObjectId newId) throws GitAPIException, IOException
    {
        if (oldId.equals(newId))
            return 0;
        int count = 0;
        for (@SuppressWarnings("unused") RevCommit commit : git.log().addRange(oldId, newId).call())
            count++;
        return count;
    }

    private static String stashNote(String stashId)
    {
        return stashId == null ? "" : " Локальные изменения сохранены в стеше " + stashId + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
