package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.junit.Test;

import dev.edt.gitflow.core.CommitOperations;
import dev.edt.gitflow.core.OperationResult;

public class CommitOperationsTest
{
    @Test
    public void generatesTaskPrefixFromBranch()
    {
        assertEquals("JIRA-1234: ", CommitOperations.generateMessage("feature/JIRA-1234-dogovora")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(CommitOperations.generateMessage("feature/no-key")); //$NON-NLS-1$
    }

    @Test
    public void requiresTaskKeyAndProtectsMainBranch() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-safe-commit-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(root.toFile()).call())
        {
            Files.writeString(root.resolve("file.txt"), "base"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("base").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            String original = git.getRepository().getBranch();
            if (!original.equals("main")) //$NON-NLS-1$
                git.branchRename().setNewName("main").call(); //$NON-NLS-1$
            Files.writeString(root.resolve("file.txt"), "changed"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            NullProgressMonitor monitor = new NullProgressMonitor();
            assertEquals(OperationResult.Kind.ERROR,
                CommitOperations.safeCommit(git.getRepository(), "no key", false, false, monitor).kind()); //$NON-NLS-1$
            assertEquals(OperationResult.Kind.NEEDS_CONFIRMATION,
                CommitOperations.safeCommit(git.getRepository(), "JIRA-1234: fix", false, false, monitor).kind()); //$NON-NLS-1$
            assertEquals(OperationResult.Kind.SUCCESS,
                CommitOperations.safeCommit(git.getRepository(), "JIRA-1234: fix", false, true, monitor).kind()); //$NON-NLS-1$
            assertEquals("JIRA-1234: fix", git.log().setMaxCount(1).call().iterator().next().getShortMessage()); //$NON-NLS-1$
        }
    }
}
