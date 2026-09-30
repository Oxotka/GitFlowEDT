package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.Test;

import dev.edt.gitflow.core.CommitBranchOperations;
import dev.edt.gitflow.core.OperationResult;

public class CommitBranchOperationsTest
{
    @Test
    public void commitsOnNewBranchAndReturnsWithResidualChanges() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-new-branch-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(root.toFile()).call())
        {
            commit(git, root, "base\n"); //$NON-NLS-1$
            String original = git.getRepository().getBranch();
            ObjectId originalHead = git.getRepository().resolve("HEAD"); //$NON-NLS-1$
            Files.writeString(root.resolve("file.txt"), "committed\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            Files.writeString(root.resolve("leftover.txt"), "untracked\n"); //$NON-NLS-1$ //$NON-NLS-2$
            OperationResult result = CommitBranchOperations.commitToNewBranch(git.getRepository(), "feature", //$NON-NLS-1$
                "new feature", false, true, false, new NullProgressMonitor()); //$NON-NLS-1$
            assertTrue(result.toString(), result.succeeded());
            assertEquals(original, git.getRepository().getBranch());
            assertEquals(originalHead, git.getRepository().resolve("HEAD")); //$NON-NLS-1$
            assertTrue(Files.exists(root.resolve("leftover.txt"))); //$NON-NLS-1$
            assertTrue(git.stashList().call().isEmpty());
            git.checkout().setName("feature").call(); //$NON-NLS-1$
            assertEquals("committed\n", Files.readString(root.resolve("file.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void movesCommitOnlyAfterSuccessfulCherryPick() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-move-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(root.toFile()).call())
        {
            commit(git, root, "base\n"); //$NON-NLS-1$
            String source = git.getRepository().getBranch();
            ObjectId parent = git.getRepository().resolve("HEAD"); //$NON-NLS-1$
            git.branchCreate().setName("target").call(); //$NON-NLS-1$
            commit(git, root, "moved\n"); //$NON-NLS-1$
            Files.writeString(root.resolve("other.txt"), "dirty\n"); //$NON-NLS-1$ //$NON-NLS-2$
            OperationResult result = CommitBranchOperations.moveLastCommit(git.getRepository(), "target", //$NON-NLS-1$
                false, true, false, new NullProgressMonitor());
            assertTrue(result.toString(), result.succeeded());
            assertEquals(source, git.getRepository().getBranch());
            assertEquals(parent, git.getRepository().resolve("HEAD")); //$NON-NLS-1$
            assertTrue(Files.exists(root.resolve("other.txt"))); //$NON-NLS-1$
            git.checkout().setName("target").call(); //$NON-NLS-1$
            assertEquals("moved\n", Files.readString(root.resolve("file.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void cherryPickConflictKeepsSourceCommitAndStash() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-move-conflict-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(root.toFile()).call())
        {
            commit(git, root, "base\n"); //$NON-NLS-1$
            String source = git.getRepository().getBranch();
            git.checkout().setCreateBranch(true).setName("target").call(); //$NON-NLS-1$
            commit(git, root, "target change\n"); //$NON-NLS-1$
            git.checkout().setName(source).call();
            commit(git, root, "source change\n"); //$NON-NLS-1$
            ObjectId sourceHead = git.getRepository().resolve("HEAD"); //$NON-NLS-1$
            Files.writeString(root.resolve("other.txt"), "dirty\n"); //$NON-NLS-1$ //$NON-NLS-2$
            OperationResult result = CommitBranchOperations.moveLastCommit(git.getRepository(), "target", //$NON-NLS-1$
                false, true, false, new NullProgressMonitor());
            assertEquals(OperationResult.Kind.CONFLICT, result.kind());
            assertEquals(sourceHead, git.getRepository().resolve("refs/heads/" + source)); //$NON-NLS-1$
            assertFalse(git.stashList().call().isEmpty());
        }
    }

    private static void commit(Git git, Path root, String contents) throws Exception
    {
        Files.writeString(root.resolve("file.txt"), contents); //$NON-NLS-1$
        git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
        git.commit().setMessage(contents).setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$
            .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
