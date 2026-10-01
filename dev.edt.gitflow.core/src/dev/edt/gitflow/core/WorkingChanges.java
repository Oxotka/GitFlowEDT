package dev.edt.gitflow.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;

import dev.edt.gitflow.core.OperationResult.Kind;

public record WorkingChanges(List<FileChange> staged, List<FileChange> unstaged)
{
    public record FileChange(String path, String state)
    {
    }

    public static WorkingChanges read(Repository repository) throws GitAPIException
    {
        Status status = Git.wrap(repository).status().call();
        List<FileChange> staged = new ArrayList<>();
        List<FileChange> unstaged = new ArrayList<>();
        add(staged, status.getAdded(), "A"); //$NON-NLS-1$
        add(staged, status.getChanged(), "M"); //$NON-NLS-1$
        add(staged, status.getRemoved(), "D"); //$NON-NLS-1$
        add(unstaged, status.getModified(), "M"); //$NON-NLS-1$
        add(unstaged, status.getMissing(), "D"); //$NON-NLS-1$
        add(unstaged, status.getUntracked(), "U"); //$NON-NLS-1$
        add(unstaged, status.getConflicting(), "C"); //$NON-NLS-1$
        staged.sort(Comparator.comparing(FileChange::path));
        unstaged.sort(Comparator.comparing(FileChange::path));
        return new WorkingChanges(List.copyOf(staged), List.copyOf(unstaged));
    }

    private static void add(List<FileChange> files, Iterable<String> paths, String state)
    {
        for (String path : paths)
            files.add(new FileChange(path, state));
    }

    public static OperationResult stage(Repository repository, String path)
    {
        try
        {
            Status status = Git.wrap(repository).status().call();
            if (!status.getModified().contains(path) && !status.getMissing().contains(path)
                && !status.getUntracked().contains(path) && !status.getConflicting().contains(path))
                return new OperationResult(Kind.ERROR, "Файл больше не находится в изменениях: " + path); //$NON-NLS-1$
            Git git = Git.wrap(repository);
            git.add().setUpdate(status.getMissing().contains(path)).addFilepattern(path).call();
            return new OperationResult(Kind.SUCCESS, "Подготовлен: " + path); //$NON-NLS-1$
        }
        catch (GitAPIException e)
        {
            return new OperationResult(Kind.ERROR, "Подготовить файл не удалось: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    public static OperationResult unstage(Repository repository, String path)
    {
        try
        {
            Git.wrap(repository).reset().addPath(path).call();
            return new OperationResult(Kind.SUCCESS, "Убран из коммита: " + path); //$NON-NLS-1$
        }
        catch (GitAPIException e)
        {
            return new OperationResult(Kind.ERROR, "Убрать файл не удалось: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    public static OperationResult resetFileToHead(Repository repository, String path)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        try
        {
            Status status = Git.wrap(repository).status().call();
            if (status.getAdded().contains(path) || status.getConflicting().contains(path)
                || !status.getModified().contains(path) && !status.getMissing().contains(path)
                && !status.getChanged().contains(path) && !status.getRemoved().contains(path))
                return new OperationResult(Kind.ERROR,
                    "Вернуть можно только файл из последнего коммита: " + path); //$NON-NLS-1$
            Git.wrap(repository).checkout().setStartPoint(Constants.HEAD).addPath(path).call();
            return new OperationResult(Kind.SUCCESS, "Файл возвращён к последнему коммиту: " + path); //$NON-NLS-1$
        }
        catch (GitAPIException e)
        {
            return new OperationResult(Kind.ERROR, "Вернуть файл не удалось: " + e.getMessage()); //$NON-NLS-1$
        }
    }
}
