package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.Test;

import dev.edt.gitflow.core.RecentHistory;

public class RecentHistoryTest
{
    @Test
    public void readsRecentCommitsWithAuthorAndLimit() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-history-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(directory.toFile()).call())
        {
            assertTrue(RecentHistory.read(git.getRepository(), 30).isEmpty());
            for (int i = 0; i < 3; i++)
            {
                Files.writeString(directory.resolve("file.txt"), Integer.toString(i)); //$NON-NLS-1$
                git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
                git.commit().setMessage("Commit " + i).setAuthor("Developer", "dev@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    .setCommitter("Developer", "dev@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            var entries = RecentHistory.read(git.getRepository(), 2);
            assertEquals(2, entries.size());
            assertEquals("Commit 2", entries.get(0).subject()); //$NON-NLS-1$
            assertEquals("Developer", entries.get(0).author()); //$NON-NLS-1$
            assertEquals("Commit 1", entries.get(1).subject()); //$NON-NLS-1$
            assertTrue(RecentHistory.read(git.getRepository(), 0).isEmpty());
        }
    }
}
