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

    public static OperationResult fetch(Repository repository, IProgressMonitor monitor)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        String branchName;
        String remote;
        try
        {
            branchName = repository.getBranch();
            remote = new BranchConfig(repository.getConfig(), branchName).getRemote();
            if (remote == null)
                remote = "origin"; //$NON-NLS-1$
            if (repository.getConfig().getString("remote", remote, "url") == null) //$NON-NLS-1$ //$NON-NLS-2$
                return new OperationResult(Kind.ERROR, "Для получения настройте remote " + remote + "."); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            return new OperationResult(Kind.ERROR, e.getMessage());
        }
        monitor.beginTask("Git Fetch", 1); //$NON-NLS-1$
        try
        {
            Git.wrap(repository).fetch().setRemote(remote).call();
            monitor.worked(1);
            return new OperationResult(Kind.NO_CHANGE,
                "Ссылки remote " + remote + " обновлены; состояние ветки пересчитано."); //$NON-NLS-1$
        }
        catch (GitAPIException e)
        {
            return new OperationResult(Kind.ERROR, "Fetch не выполнен: " + e.getMessage()); //$NON-NLS-1$
        }
        finally
        {
            monitor.done();
        }
    }

    public static OperationResult smartPull(Repository repository, IProgressMonitor monitor)
    {
        return smartPull(repository, false, monitor);
    }

    public static OperationResult smartPull(Repository repository, boolean confirmTrack,
        IProgressMonitor monitor)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        try
        {
            BranchConfig branch = new BranchConfig(repository.getConfig(), repository.getBranch());
            if (branch.getRemote() == null || branch.getRemoteTrackingBranch() == null)
                return trackAndPull(repository, confirmTrack, monitor);
        }
        catch (IOException e)
        {
            return new OperationResult(Kind.ERROR, e.getMessage());
        }
        String stashId = null;
        boolean workspaceChanged = false;
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
                workspaceChanged = true;
            }
            monitor.worked(1);
            monitor.subTask("Обновление ветки"); //$NON-NLS-1$
            workspaceChanged = true;
            RebaseResult rebase = git.rebase().setUpstream(upstream).call();
            monitor.worked(1);
            if (!rebase.getStatus().isSuccessful())
                return new OperationResult(Kind.CONFLICT,
                    "Rebase остановлен: " + rebase.getStatus() + stashNote(stashId), true); //$NON-NLS-1$

            if (stashId != null)
            {
                monitor.subTask("Восстановление локальных изменений"); //$NON-NLS-1$
                StashOperations.Result restored = StashOperations.applyAndDrop(repository, stashId);
                if (restored.outcome() != StashOperations.Outcome.APPLIED)
                    return new OperationResult(Kind.CONFLICT,
                        "Обновление прошло, но локальные изменения не восстановлены: " //$NON-NLS-1$
                            + restored.detail() + " Стеш сохранён.", true); //$NON-NLS-1$
            }
            monitor.worked(1);
            return fetched == 0
                ? new OperationResult(Kind.NO_CHANGE, "Ветка актуальна, локальные изменения восстановлены.", //$NON-NLS-1$
                    workspaceChanged)
                : new OperationResult(Kind.SUCCESS, "Получено коммитов: " + fetched //$NON-NLS-1$
                    + ". Локальные изменения восстановлены.", workspaceChanged); //$NON-NLS-1$
        }
        catch (GitAPIException | IOException e)
        {
            return new OperationResult(Kind.ERROR, e.getMessage() + stashNote(stashId), workspaceChanged);
        }
        finally
        {
            monitor.done();
        }
    }

    private static OperationResult trackAndPull(Repository repository, boolean confirmed,
        IProgressMonitor monitor)
    {
        if (repository.getConfig().getString("remote", "origin", "url") == null) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return new OperationResult(Kind.ERROR, "Для получения настройте remote origin."); //$NON-NLS-1$
        try
        {
            String name = repository.getBranch();
            if (!confirmed)
                return new OperationResult(Kind.NEEDS_CONFIRMATION,
                    "У ветки " + name + " нет upstream. Найти её в origin и настроить получение?"); //$NON-NLS-1$ //$NON-NLS-2$
            Git.wrap(repository).fetch().setRemote("origin").call(); //$NON-NLS-1$
            if (repository.resolve("refs/remotes/origin/" + name) == null) //$NON-NLS-1$
                return new OperationResult(Kind.ERROR,
                    "В origin нет ветки " + name + ". Для первой отправки используйте умную отправку."); //$NON-NLS-1$ //$NON-NLS-2$
            configureUpstream(repository, name);
            return smartPull(repository, false, monitor);
        }
        catch (GitAPIException | IOException e)
        {
            return new OperationResult(Kind.ERROR, "Настроить получение не удалось: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    public static OperationResult smartPush(Repository repository, IProgressMonitor monitor)
    {
        return smartPush(repository, false, monitor);
    }

    public static OperationResult smartPush(Repository repository, boolean confirmPublish,
        IProgressMonitor monitor)
    {
        try
        {
            String name = repository.getBranch();
            BranchConfig branch = new BranchConfig(repository.getConfig(), name);
            if (branch.getRemote() == null || branch.getMerge() == null)
                return publishBranch(repository, name, confirmPublish, monitor);
        }
        catch (IOException e)
        {
            return new OperationResult(Kind.ERROR, e.getMessage());
        }
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
            int updated = push(git, remote, name, merge);
            if (updated < 0)
                return new OperationResult(Kind.ERROR,
                    "Получение прошло, но push отклонён. Повторите умную отправку.", //$NON-NLS-1$
                    pulled.workspaceChanged());
            return updated == 0
                ? new OperationResult(Kind.NO_CHANGE, "Ветка уже синхронизирована.", //$NON-NLS-1$
                    pulled.workspaceChanged())
                : new OperationResult(Kind.SUCCESS, pulled.message() + " Отправлена ветка " + name, //$NON-NLS-1$
                    pulled.workspaceChanged());
        }
        catch (GitAPIException | IOException e)
        {
            return new OperationResult(Kind.ERROR,
                "Получение прошло, но отправка не удалась: " + e.getMessage(), //$NON-NLS-1$
                pulled.workspaceChanged());
        }
    }

    private static OperationResult publishBranch(Repository repository, String name,
        boolean confirmed, IProgressMonitor monitor)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        if (repository.getConfig().getString("remote", "origin", "url") == null) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return new OperationResult(Kind.ERROR, "Для публикации ветки настройте remote origin."); //$NON-NLS-1$
        if (!confirmed)
            return new OperationResult(Kind.NEEDS_CONFIRMATION,
                "У ветки " + name + " нет upstream. Найти её в origin или опубликовать и настроить upstream?"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Git git = Git.wrap(repository);
            git.fetch().setRemote("origin").call(); //$NON-NLS-1$
            if (repository.resolve("refs/remotes/origin/" + name) != null) //$NON-NLS-1$
            {
                configureUpstream(repository, name);
                return smartPush(repository, true, monitor);
            }
            int updated = push(git, "origin", name, "refs/heads/" + name); //$NON-NLS-1$ //$NON-NLS-2$
            if (updated < 0)
                return new OperationResult(Kind.ERROR,
                    "Ветка появилась на сервере во время отправки. Повторите умную отправку."); //$NON-NLS-1$
            configureUpstream(repository, name);
            return new OperationResult(Kind.SUCCESS,
                "Ветка " + name + " опубликована в origin; upstream настроен."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (GitAPIException e)
        {
            return new OperationResult(Kind.ERROR, "Опубликовать ветку не удалось: " + e.getMessage()); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            return new OperationResult(Kind.ERROR,
                "Ветку отправили или нашли в origin, но upstream не удалось сохранить: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void configureUpstream(Repository repository, String name) throws IOException
    {
        repository.getConfig().setString("branch", name, "remote", "origin"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        repository.getConfig().setString("branch", name, "merge", "refs/heads/" + name); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        repository.getConfig().save();
    }

    private static int push(Git git, String remote, String name, String destination)
        throws GitAPIException
    {
        RefSpec spec = new RefSpec("refs/heads/" + name + ":" + destination); //$NON-NLS-1$ //$NON-NLS-2$
        int updated = 0;
        for (PushResult result : git.push().setRemote(remote).setRefSpecs(spec).call())
        {
            for (RemoteRefUpdate ref : result.getRemoteUpdates())
            {
                if (ref.getStatus() == RemoteRefUpdate.Status.OK)
                    updated++;
                else if (ref.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE)
                    return -1;
            }
        }
        return updated;
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
