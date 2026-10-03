package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.junit.Test;

import dev.edt.gitflow.core.StashOperations;
import dev.edt.gitflow.core.StashOperations.Outcome;

public class StashOperationsTest
{
    @Test
    public void restoresTrackedAndUntrackedFiles() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-stash-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(directory.toFile()).call())
        {
            Path tracked = directory.resolve("tracked.txt"); //$NON-NLS-1$
            Files.writeString(tracked, "base\n"); //$NON-NLS-1$
            git.add().addFilepattern("tracked.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("base").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            NullProgressMonitor monitor = new NullProgressMonitor();
            assertFalse(StashOperations.hasStash(git.getRepository()));
            assertEquals(Outcome.NO_CHANGES, StashOperations.quickStash(git.getRepository(), true, monitor).outcome());
            Files.writeString(tracked, "changed\n"); //$NON-NLS-1$
            Path untracked = directory.resolve("new.txt"); //$NON-NLS-1$
            Files.writeString(untracked, "new\n"); //$NON-NLS-1$
            StashOperations.Result stash = StashOperations.quickStash(git.getRepository(), true, monitor);
            assertEquals(Outcome.CREATED, stash.outcome());
            assertTrue(StashOperations.hasStash(git.getRepository()));
            assertNotNull(stash.stashId());
            assertFalse(Files.exists(untracked));
            assertEquals(Outcome.APPLIED, StashOperations.quickPop(git.getRepository(), monitor).outcome());
            assertEquals("changed\n", Files.readString(tracked)); //$NON-NLS-1$
            assertTrue(Files.exists(untracked));
            assertTrue(git.stashList().call().isEmpty());
            assertFalse(StashOperations.hasStash(git.getRepository()));
        }
    }

    @Test
    public void keepsStashOnApplyConflict() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-conflict-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(directory.toFile()).call())
        {
            Path file = directory.resolve("file.txt"); //$NON-NLS-1$
            Files.writeString(file, "base\n"); //$NON-NLS-1$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("base").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(file, "stashed\n"); //$NON-NLS-1$
            NullProgressMonitor monitor = new NullProgressMonitor();
            assertEquals(Outcome.CREATED, StashOperations.quickStash(git.getRepository(), true, monitor).outcome());
            Files.writeString(file, "other\n"); //$NON-NLS-1$
            assertEquals(Outcome.CONFLICTS, StashOperations.quickPop(git.getRepository(), monitor).outcome());
            assertFalse(git.stashList().call().isEmpty());
        }
    }
}
