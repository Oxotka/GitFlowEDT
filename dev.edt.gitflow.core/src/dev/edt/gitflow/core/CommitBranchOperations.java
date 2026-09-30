package dev.edt.gitflow.core;

import java.io.IOException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;

import dev.edt.gitflow.core.OperationResult.Kind;

public final class CommitBranchOperations
{
    private CommitBranchOperations()
    {
    }

    public static OperationResult commitToNewBranch(Repository repository, String name, String message,
        boolean stageTracked, boolean returnToOriginal, boolean push, IProgressMonitor monitor)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        if (!validNewBranch(repository, name))
            return new OperationResult(Kind.ERROR, "Имя новой ветки некорректно или уже занято."); //$NON-NLS-1$
        if (message == null || message.isBlank())
            return new OperationResult(Kind.ERROR, "Введите сообщение коммита."); //$NON-NLS-1$
        String stashId = null;
        String original = null;
        monitor.beginTask("Коммит в новую ветку", 5); //$NON-NLS-1$
        try
        {
            Git git = Git.wrap(repository);
            original = repository.getBranch();
            Status status = git.status().call();
            if (status.isClean())
                return new OperationResult(Kind.NO_CHANGE, "Изменений для коммита нет."); //$NON-NLS-1$
            if (!hasStaged(status) && !stageTracked)
                return new OperationResult(Kind.NEEDS_CONFIRMATION,
                    "Подготовленных изменений нет. Добавить все отслеживаемые изменения в коммит?"); //$NON-NLS-1$
            if (!hasStaged(status) && status.getModified().isEmpty() && status.getMissing().isEmpty())
                return new OperationResult(Kind.ERROR, "Нет отслеживаемых изменений для коммита."); //$NON-NLS-1$
            RevCommit stash = git.stashCreate().setIncludeUntracked(true).call();
            if (stash == null)
                return new OperationResult(Kind.ERROR, "Не удалось сохранить изменения перед переключением."); //$NON-NLS-1$
            stashId = stash.getId().name();
            monitor.worked(1);
            git.checkout().setCreateBranch(true).setName(name).call();
            monitor.worked(1);
            StashOperations.Result restored = StashOperations.applyAndDrop(repository, stashId);
            if (restored.outcome() != StashOperations.Outcome.APPLIED)
                return new OperationResult(Kind.CONFLICT,
                    "Новая ветка создана, но изменения остались в стеше: " + restored.detail()); //$NON-NLS-1$
            stashId = null;
            if (stageTracked)
                git.add().setUpdate(true).addFilepattern(".").call(); //$NON-NLS-1$
            if (!hasStaged(git.status().call()))
                return new OperationResult(Kind.ERROR,
                    "Новая ветка создана, но отслеживаемых изменений для коммита нет."); //$NON-NLS-1$
            git.commit().setMessage(message).call();
            monitor.worked(1);
            if (returnToOriginal)
            {
                OperationResult returned = BranchOperations.checkout(repository, original, false,
                    new NullProgressMonitor());
                if (!returned.succeeded())
                    return new OperationResult(Kind.CONFLICT,
                        "Коммит создан в ветке " + name + ", но возврат не завершён: " //$NON-NLS-1$ //$NON-NLS-2$
                            + returned.message());
            }
            monitor.worked(1);
            if (push)
            {
                for (PushResult result : git.push().setRemote("origin") //$NON-NLS-1$
                    .add("refs/heads/" + name).call()) //$NON-NLS-1$
                {
                    for (RemoteRefUpdate ref : result.getRemoteUpdates())
                    {
                        if (ref.getStatus() != RemoteRefUpdate.Status.OK
                            && ref.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE)
                            return new OperationResult(Kind.ERROR,
                                "Коммит создан локально, но отправка не удалась: " + ref.getStatus()); //$NON-NLS-1$
                    }
                }
            }
            monitor.worked(1);
            return new OperationResult(Kind.SUCCESS, "Коммит создан в ветке " + name //$NON-NLS-1$
                + (returnToOriginal ? "; исходная ветка восстановлена." : ".")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (GitAPIException | IOException e)
        {
            return new OperationResult(Kind.ERROR, e.getMessage()
                + (stashId == null ? "" : " Изменения сохранены в стеше " + stashId)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            monitor.done();
        }
    }

    public static OperationResult moveLastCommit(Repository repository, String target, boolean create,
        boolean returnToOriginal, boolean confirmPushed, IProgressMonitor monitor)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        if (target == null || !org.eclipse.jgit.api.CreateBranchCommand.isValidBranchName(target))
            return new OperationResult(Kind.ERROR, "Некорректное имя ветки."); //$NON-NLS-1$
        String stashId = null;
        monitor.beginTask("Перенос коммита", 6); //$NON-NLS-1$
        try (RevWalk walk = new RevWalk(repository))
        {
            Git git = Git.wrap(repository);
            String original = repository.getBranch();
            if (target.equals(original))
                return new OperationResult(Kind.ERROR, "Выберите другую ветку."); //$NON-NLS-1$
            ObjectId headId = repository.resolve("HEAD"); //$NON-NLS-1$
            if (headId == null)
                return new OperationResult(Kind.ERROR, "В ветке нет коммитов."); //$NON-NLS-1$
            RevCommit head = walk.parseCommit(headId);
            if (head.getParentCount() != 1)
                return new OperationResult(Kind.ERROR, "Корневой или merge-коммит нельзя перенести."); //$NON-NLS-1$
            if (BranchOperations.isPushed(repository, head, walk) && !confirmPushed)
                return new OperationResult(Kind.NEEDS_CONFIRMATION,
                    "Коммит уже отправлен. Перенос изменит историю ветки и потребует force push."); //$NON-NLS-1$
            ObjectId targetId = repository.resolve("refs/heads/" + target); //$NON-NLS-1$
            if (create == (targetId != null))
                return new OperationResult(Kind.ERROR,
                    create ? "Ветка уже существует." : "Целевая ветка не найдена."); //$NON-NLS-1$ //$NON-NLS-2$
            if (!git.status().call().isClean())
            {
                RevCommit stash = git.stashCreate().setIncludeUntracked(true).call();
                if (stash == null)
                    return new OperationResult(Kind.ERROR, "Не удалось сохранить локальные изменения."); //$NON-NLS-1$
                stashId = stash.getId().name();
            }
            monitor.worked(1);
            if (create)
            {
                // A new branch can point at the existing commit; no cherry-pick is needed.
                git.branchCreate().setName(target).setStartPoint(headId.name()).call();
            }
            else
            {
                git.checkout().setName(target).call();
                CherryPickResult picked = git.cherryPick().include(head).call();
                if (picked.getStatus() != CherryPickResult.CherryPickStatus.OK)
                    return new OperationResult(Kind.CONFLICT,
                        "Cherry-pick остановлен. Исходная ветка не изменена." + stashNote(stashId)); //$NON-NLS-1$
                git.checkout().setName(original).call();
            }
            monitor.worked(2);
            if (!headId.equals(repository.resolve("HEAD")) || !git.status().call().isClean()) //$NON-NLS-1$
                return new OperationResult(Kind.ERROR,
                    "Исходная ветка изменилась; коммит из неё не удалён." + stashNote(stashId)); //$NON-NLS-1$
            // All pre-existing changes are in the stash and the target contains the commit.
            git.reset().setMode(ResetType.HARD).setRef(head.getParent(0).getId().name()).call();
            monitor.worked(1);
            if (!returnToOriginal)
                git.checkout().setName(target).call();
            monitor.worked(1);
            if (stashId != null)
            {
                StashOperations.Result restored = StashOperations.applyAndDrop(repository, stashId);
                if (restored.outcome() != StashOperations.Outcome.APPLIED)
                    return new OperationResult(Kind.CONFLICT,
                        "Коммит перенесён, но локальные изменения остались в стеше: " //$NON-NLS-1$
                            + restored.detail());
            }
            monitor.worked(1);
            return new OperationResult(Kind.SUCCESS, "Коммит перенесён в ветку " + target + "."); //$NON-NLS-1$ //$NON-NLS-2$
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

    private static boolean validNewBranch(Repository repository, String name)
    {
        if (name == null || !org.eclipse.jgit.api.CreateBranchCommand.isValidBranchName(name))
            return false;
        try
        {
            return repository.resolve("refs/heads/" + name) == null; //$NON-NLS-1$
        }
        catch (IOException e)
        {
            return false;
        }
    }

    private static boolean hasStaged(Status status)
    {
        return !status.getAdded().isEmpty() || !status.getChanged().isEmpty()
            || !status.getRemoved().isEmpty();
    }

    private static String stashNote(String stashId)
    {
        return stashId == null ? "" : " Изменения сохранены в стеше " + stashId + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
