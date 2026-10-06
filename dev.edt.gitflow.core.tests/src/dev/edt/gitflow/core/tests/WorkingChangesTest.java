package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.junit.Test;

import dev.edt.gitflow.core.WorkingChanges;

public class WorkingChangesTest
{
    @Test
    public void stagesCachedSelectionWithoutIncludingLaterChanges() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-cached-stage-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            Files.writeString(work.resolve("modified.txt"), "base"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("deleted.txt"), "base"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern(".").call(); //$NON-NLS-1$
            git.commit().setMessage("base").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("modified.txt"), "changed"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.delete(work.resolve("deleted.txt")); //$NON-NLS-1$
            Files.writeString(work.resolve("new.txt"), "new"); //$NON-NLS-1$ //$NON-NLS-2$
            var selection = WorkingChanges.read(git.getRepository()).unstaged();
            Files.writeString(work.resolve("later.txt"), "keep out"); //$NON-NLS-1$ //$NON-NLS-2$

            assertTrue(WorkingChanges.stage(git.getRepository(), selection).succeeded());
            var status = git.status().call();
            assertTrue(status.getChanged().contains("modified.txt")); //$NON-NLS-1$
            assertTrue(status.getRemoved().contains("deleted.txt")); //$NON-NLS-1$
            assertTrue(status.getAdded().contains("new.txt")); //$NON-NLS-1$
            assertTrue(status.getUntracked().contains("later.txt")); //$NON-NLS-1$

            assertTrue(WorkingChanges.unstage(git.getRepository(),
                selection.stream().map(WorkingChanges.FileChange::path).toList()).succeeded());
            assertTrue(WorkingChanges.read(git.getRepository()).staged().isEmpty());
            assertEquals("changed", Files.readString(work.resolve("modified.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(Files.exists(work.resolve("deleted.txt"))); //$NON-NLS-1$
        }
    }

    @Test
    public void stageAndUnstageKeepOtherChangesSeparate() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-changes-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            Files.writeString(work.resolve("tracked.txt"), "base"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("tracked.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("base").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("tracked.txt"), "changed"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("new.txt"), "new"); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(2, WorkingChanges.read(git.getRepository()).unstaged().size());
            assertTrue(WorkingChanges.stage(git.getRepository(), "tracked.txt").succeeded()); //$NON-NLS-1$
            WorkingChanges staged = WorkingChanges.read(git.getRepository());
            assertEquals("tracked.txt", staged.staged().get(0).path()); //$NON-NLS-1$
            assertEquals("new.txt", staged.unstaged().get(0).path()); //$NON-NLS-1$
            assertTrue(WorkingChanges.unstage(git.getRepository(), "tracked.txt").succeeded()); //$NON-NLS-1$
            assertTrue(WorkingChanges.read(git.getRepository()).staged().isEmpty());
        }
    }

    @Test
    public void stagesDeletedTrackedFile() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-delete-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            Files.writeString(work.resolve("tracked.txt"), "base"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("tracked.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("base").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            Files.delete(work.resolve("tracked.txt")); //$NON-NLS-1$
            assertTrue(WorkingChanges.stage(git.getRepository(), "tracked.txt").succeeded()); //$NON-NLS-1$
            assertEquals("D", WorkingChanges.read(git.getRepository()).staged().get(0).state()); //$NON-NLS-1$
        }
    }

    @Test
    public void resetRestoresHeadAndClearsStagedChanges() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-discard-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            Path file = work.resolve("tracked.txt"); //$NON-NLS-1$
            Files.writeString(file, "base"); //$NON-NLS-1$
            git.add().addFilepattern("tracked.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("base").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(file, "staged"); //$NON-NLS-1$
            git.add().addFilepattern("tracked.txt").call(); //$NON-NLS-1$
            Files.writeString(file, "unstaged"); //$NON-NLS-1$

            assertTrue(WorkingChanges.resetFileToHead(git.getRepository(), "tracked.txt").succeeded()); //$NON-NLS-1$
            assertEquals("base", Files.readString(file)); //$NON-NLS-1$
            assertTrue(WorkingChanges.read(git.getRepository()).staged().isEmpty());
            assertTrue(WorkingChanges.read(git.getRepository()).unstaged().isEmpty());
        }
    }

    @Test
    public void resetDoesNotDeleteUntrackedFile() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-discard-new-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            Path file = work.resolve("new.txt"); //$NON-NLS-1$
            Files.writeString(file, "keep"); //$NON-NLS-1$
            assertEquals(dev.edt.gitflow.core.OperationResult.Kind.ERROR,
                WorkingChanges.resetFileToHead(git.getRepository(), "new.txt").kind()); //$NON-NLS-1$
            assertEquals("keep", Files.readString(file)); //$NON-NLS-1$
            git.add().addFilepattern("new.txt").call(); //$NON-NLS-1$
            Files.writeString(file, "changed"); //$NON-NLS-1$
            assertEquals(dev.edt.gitflow.core.OperationResult.Kind.ERROR,
                WorkingChanges.resetFileToHead(git.getRepository(), "new.txt").kind()); //$NON-NLS-1$
            assertEquals("changed", Files.readString(file)); //$NON-NLS-1$
        }
    }

    @Test
    public void deletesOnlyUntrackedFile() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-delete-untracked-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            Path tracked = work.resolve("tracked.txt"); //$NON-NLS-1$
            Path untracked = work.resolve("new.txt"); //$NON-NLS-1$
            Files.writeString(tracked, "base"); //$NON-NLS-1$
            git.add().addFilepattern("tracked.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("base").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(untracked, "new"); //$NON-NLS-1$

            List<String> deleted = new ArrayList<>();
            git.getRepository().getListenerList().addWorkingTreeModifiedListener(
                event -> deleted.addAll(event.getDeleted()));
            assertTrue(WorkingChanges.deleteUntrackedFile(git.getRepository(), "new.txt").succeeded()); //$NON-NLS-1$
            assertFalse(Files.exists(untracked));
            assertEquals(List.of("new.txt"), deleted); //$NON-NLS-1$
            assertEquals(dev.edt.gitflow.core.OperationResult.Kind.ERROR,
                WorkingChanges.deleteUntrackedFile(git.getRepository(), "tracked.txt").kind()); //$NON-NLS-1$
            assertEquals("base", Files.readString(tracked)); //$NON-NLS-1$
        }
    }

    @Test
    public void stagesAndUnstagesAllChangesInOneOperation() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-stage-all-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            Files.writeString(work.resolve("modified.txt"), "base"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("deleted.txt"), "base"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern(".").call(); //$NON-NLS-1$
            git.commit().setMessage("base").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("modified.txt"), "changed"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.delete(work.resolve("deleted.txt")); //$NON-NLS-1$
            Files.writeString(work.resolve("new.txt"), "new"); //$NON-NLS-1$ //$NON-NLS-2$

            assertTrue(WorkingChanges.stageAll(git.getRepository()).succeeded());
            assertEquals(3, WorkingChanges.read(git.getRepository()).staged().size());
            assertTrue(WorkingChanges.read(git.getRepository()).unstaged().isEmpty());
            assertTrue(WorkingChanges.unstageAll(git.getRepository()).succeeded());
            assertTrue(WorkingChanges.read(git.getRepository()).staged().isEmpty());
            assertEquals(3, WorkingChanges.read(git.getRepository()).unstaged().size());
        }
    }

    @Test
    public void resetRestoresDeletedTrackedFile() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-reset-deleted-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            Path file = work.resolve("tracked.txt"); //$NON-NLS-1$
            Files.writeString(file, "base"); //$NON-NLS-1$
            git.add().addFilepattern("tracked.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("base").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            Files.delete(file);

            assertTrue(WorkingChanges.resetFileToHead(git.getRepository(), "tracked.txt").succeeded()); //$NON-NLS-1$
            assertEquals("base", Files.readString(file)); //$NON-NLS-1$
            assertTrue(WorkingChanges.read(git.getRepository()).unstaged().isEmpty());
        }
    }

    @Test
    public void discardUnstagedPreservesStagedFiles() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-reset-all-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            Path tracked = work.resolve("tracked.txt"); //$NON-NLS-1$
            Path deleted = work.resolve("deleted.txt"); //$NON-NLS-1$
            Path stagedNew = work.resolve("staged-new.txt"); //$NON-NLS-1$
            Path untracked = work.resolve("untracked.txt"); //$NON-NLS-1$
            Files.writeString(tracked, "base"); //$NON-NLS-1$
            Files.writeString(deleted, "base"); //$NON-NLS-1$
            git.add().addFilepattern(".").call(); //$NON-NLS-1$
            git.commit().setMessage("base").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$
                .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$

            Files.writeString(tracked, "staged"); //$NON-NLS-1$
            git.add().addFilepattern("tracked.txt").call(); //$NON-NLS-1$
            Files.writeString(tracked, "unstaged"); //$NON-NLS-1$
            Files.delete(deleted);
            Files.writeString(stagedNew, "new"); //$NON-NLS-1$
            git.add().addFilepattern("staged-new.txt").call(); //$NON-NLS-1$
            Files.writeString(untracked, "keep"); //$NON-NLS-1$

            List<String> removed = new ArrayList<>();
            git.getRepository().getListenerList().addWorkingTreeModifiedListener(
                event -> removed.addAll(event.getDeleted()));
            assertTrue(WorkingChanges.discardUnstagedChanges(git.getRepository()).succeeded());
            assertEquals("staged", Files.readString(tracked)); //$NON-NLS-1$
            assertEquals("base", Files.readString(deleted)); //$NON-NLS-1$
            assertEquals("new", Files.readString(stagedNew)); //$NON-NLS-1$
            assertFalse(Files.exists(untracked));
            assertEquals(List.of("untracked.txt"), removed); //$NON-NLS-1$
            WorkingChanges changes = WorkingChanges.read(git.getRepository());
            assertEquals(2, changes.staged().size());
            assertTrue(changes.unstaged().isEmpty());
        }
    }
}
