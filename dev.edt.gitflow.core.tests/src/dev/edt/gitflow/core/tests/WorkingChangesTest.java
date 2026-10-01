package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.Test;

import dev.edt.gitflow.core.WorkingChanges;

public class WorkingChangesTest
{
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
}
