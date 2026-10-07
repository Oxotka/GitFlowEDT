package dev.edt.gitflow.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.events.WorkingTreeModifiedEvent;
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

    public static OperationResult stageAll(Repository repository)
    {
        try
        {
            return stage(repository, read(repository).unstaged());
        }
        catch (GitAPIException e)
        {
            return new OperationResult(Kind.ERROR, "Подготовить все изменения не удалось: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    public static OperationResult stage(Repository repository, List<FileChange> files)
    {
        try
        {
            Git git = Git.wrap(repository);
            for (boolean deleted : new boolean[] {false, true})
            {
                List<String> paths = files.stream().filter(file -> deleted == "D".equals(file.state())) //$NON-NLS-1$
                    .map(FileChange::path).toList();
                if (!paths.isEmpty())
                {
                    var add = git.add().setUpdate(deleted);
                    paths.forEach(add::addFilepattern);
                    add.call();
                }
            }
            return new OperationResult(Kind.SUCCESS, files.size() == 1
                ? "Подготовлен: " + files.get(0).path() : "Подготовка всех изменений завершена."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (GitAPIException e)
        {
            return new OperationResult(Kind.ERROR, "Подготовить все изменения не удалось: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    public static OperationResult stage(Repository repository, List<FileChange> files, IProgressMonitor monitor)
    {
        IProgressMonitor progress = monitor == null ? new NullProgressMonitor() : monitor;
        for (int offset = 0; offset < files.size(); offset += 32)
        {
            if (progress.isCanceled())
                return new OperationResult(Kind.CANCELLED, "Подготовка изменений отменена"); //$NON-NLS-1$
            List<FileChange> batch = files.subList(offset, Math.min(offset + 32, files.size()));
            progress.subTask(batch.get(0).path());
            OperationResult result = stage(repository, batch);
            if (!result.succeeded())
                return result;
        }
        return progress.isCanceled()
            ? new OperationResult(Kind.CANCELLED, "Подготовка изменений отменена") //$NON-NLS-1$
            : new OperationResult(Kind.SUCCESS, "Подготовка изменений завершена"); //$NON-NLS-1$
    }

    public static OperationResult unstage(Repository repository, List<String> paths)
    {
        try
        {
            if (!paths.isEmpty())
            {
                var reset = Git.wrap(repository).reset();
                paths.forEach(reset::addPath);
                reset.call();
            }
            return new OperationResult(Kind.SUCCESS, "Все файлы возвращены в изменения."); //$NON-NLS-1$
        }
        catch (GitAPIException e)
        {
            return new OperationResult(Kind.ERROR, "Вернуть все файлы в изменения не удалось: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    public static OperationResult unstageAll(Repository repository)
    {
        try
        {
            return unstage(repository, read(repository).staged().stream().map(FileChange::path).toList());
        }
        catch (GitAPIException e)
        {
            return new OperationResult(Kind.ERROR, "Вернуть все файлы в изменения не удалось: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    public static OperationResult resetFileToHead(Repository repository, String path)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        try
        {
            Status status = Git.wrap(repository).status().addPath(path).call();
            if (status.getAdded().contains(path) || status.getConflicting().contains(path)
                || !status.getModified().contains(path) && !status.getMissing().contains(path)
                && !status.getChanged().contains(path) && !status.getRemoved().contains(path))
                return new OperationResult(Kind.ERROR,
                    "Вернуть можно только файл из последнего коммита: " + path); //$NON-NLS-1$
            Git.wrap(repository).checkout().setStartPoint(Constants.HEAD).addPath(path).call();
            return new OperationResult(Kind.SUCCESS, "Файл возвращен к последнему коммиту: " + path, true); //$NON-NLS-1$
        }
        catch (GitAPIException e)
        {
            return new OperationResult(Kind.ERROR, "Вернуть файл не удалось: " + e.getMessage(), true); //$NON-NLS-1$
        }
    }

    public static OperationResult deleteUntrackedFile(Repository repository, String path)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        try
        {
            Status status = Git.wrap(repository).status().addPath(path).call();
            if (!status.getUntracked().contains(path))
                return new OperationResult(Kind.ERROR, "Удалить можно только новый неотслеживаемый файл: " + path); //$NON-NLS-1$
            Path root = repository.getWorkTree().toPath().toAbsolutePath().normalize();
            Path file = root.resolve(path).normalize();
            if (!file.startsWith(root) || file.equals(root) || Files.isDirectory(file))
                return new OperationResult(Kind.ERROR, "Недопустимый путь нового файла: " + path); //$NON-NLS-1$
            Files.delete(file);
            repository.fireEvent(new WorkingTreeModifiedEvent(null, List.of(path)));
            return new OperationResult(Kind.SUCCESS, "Новый файл удален: " + path, true); //$NON-NLS-1$
        }
        catch (GitAPIException | IOException e)
        {
            return new OperationResult(Kind.ERROR, "Удалить новый файл не удалось: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    public static OperationResult discardUnstagedChanges(Repository repository)
    {
        if (!RepositorySupport.isSafe(repository))
            return new OperationResult(Kind.ERROR, "Репозиторий занят другой Git-операцией."); //$NON-NLS-1$
        boolean workspaceChanged = false;
        Set<String> deletedFiles = new LinkedHashSet<>();
        try
        {
            Git git = Git.wrap(repository);
            Status status = git.status().call();
            Set<String> paths = new LinkedHashSet<>();
            paths.addAll(status.getModified());
            paths.addAll(status.getMissing());
            Set<String> newFiles = new LinkedHashSet<>(status.getUntracked());
            workspaceChanged = !paths.isEmpty() || !newFiles.isEmpty();
            if (!paths.isEmpty())
            {
                var checkout = git.checkout();
                paths.forEach(checkout::addPath);
                checkout.call();
            }
            Path root = repository.getWorkTree().toPath().toAbsolutePath().normalize();
            for (String path : newFiles)
            {
                Path file = root.resolve(path).normalize();
                if (file.startsWith(root) && !file.equals(root)
                    && (!Files.isDirectory(file) || Files.isSymbolicLink(file)))
                {
                    if (Files.deleteIfExists(file))
                        deletedFiles.add(path);
                }
            }
            return new OperationResult(Kind.SUCCESS,
                "Неподготовленные изменения отменены; подготовленные изменения сохранены.", //$NON-NLS-1$
                workspaceChanged);
        }
        catch (GitAPIException | IOException e)
        {
            return new OperationResult(Kind.ERROR, "Вернуть изменения не удалось: " + e.getMessage(), //$NON-NLS-1$
                workspaceChanged);
        }
        finally
        {
            if (!deletedFiles.isEmpty())
                repository.fireEvent(new WorkingTreeModifiedEvent(null, deletedFiles));
        }
    }
}
