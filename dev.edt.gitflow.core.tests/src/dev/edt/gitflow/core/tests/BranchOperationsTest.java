package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

import dev.edt.gitflow.core.BranchOperations;
import dev.edt.gitflow.core.OperationResult;
import dev.edt.gitflow.core.WorkingChanges;

public class BranchOperationsTest
{
    @Test
    public void checkoutKeepsStagedAndUnstagedFilesInTheirGroups() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-switch-index-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            commit(git, work, "base\n"); //$NON-NLS-1$
            Files.writeString(work.resolve("file.txt"), "staged\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            Files.writeString(work.resolve("untracked.txt"), "new\n"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(BranchOperations.checkout(git.getRepository(), "feature", true, //$NON-NLS-1$
                new NullProgressMonitor()).succeeded());
            WorkingChanges changes = WorkingChanges.read(git.getRepository());
            assertEquals("file.txt", changes.staged().get(0).path()); //$NON-NLS-1$
            assertEquals("untracked.txt", changes.unstaged().get(0).path()); //$NON-NLS-1$
            assertTrue(git.stashList().call().isEmpty());
        }
    }

    @Test
    public void undoPublishedCommitRequiresConfirmation() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-published-"); //$NON-NLS-1$
        Path bare = directory.resolve("origin.git"); //$NON-NLS-1$
        Path work = directory.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git git = Git.init().setDirectory(work.toFile()).call())
        {
            commit(git, work, "base\n"); //$NON-NLS-1$
            git.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            String branch = git.getRepository().getBranch();
            git.push().setRemote("origin").add(git.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            git.getRepository().getConfig().setString("branch", branch, "remote", "origin"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            git.getRepository().getConfig().setString("branch", branch, "merge", //$NON-NLS-1$ //$NON-NLS-2$
                git.getRepository().getFullBranch());
            git.getRepository().getConfig().save();
            commit(git, work, "published\n"); //$NON-NLS-1$
            git.push().setRemote("origin").add(git.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            git.fetch().setRemote("origin").call(); //$NON-NLS-1$
            ObjectId head = git.getRepository().resolve("HEAD"); //$NON-NLS-1$
            OperationResult result = BranchOperations.undoLastCommit(git.getRepository(), false, false,
                new NullProgressMonitor());
            assertEquals(OperationResult.Kind.NEEDS_CONFIRMATION, result.kind());
            assertEquals(head, git.getRepository().resolve("HEAD")); //$NON-NLS-1$
        }
    }

    @Test
    public void checkoutRestoresDirtyTreeAndUndoKeepsChanges() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-branch-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(directory.toFile()).call())
        {
            commit(git, directory, "base\n"); //$NON-NLS-1$
            String original = git.getRepository().getBranch();
            Files.writeString(directory.resolve("file.txt"), "dirty\n"); //$NON-NLS-1$ //$NON-NLS-2$
            OperationResult switched = BranchOperations.checkout(git.getRepository(), "feature", true, //$NON-NLS-1$
                new NullProgressMonitor());
            assertTrue(switched.toString(), switched.succeeded());
            assertEquals("dirty\n", Files.readString(directory.resolve("file.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(git.stashList().call().isEmpty());
            commit(git, directory, "feature\n"); //$NON-NLS-1$
            OperationResult undone = BranchOperations.undoLastCommit(git.getRepository(), false, false,
                new NullProgressMonitor());
            assertTrue(undone.toString(), undone.succeeded());
            assertEquals("feature\n", Files.readString(directory.resolve("file.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(git.status().call().getModified().isEmpty());
            assertTrue(BranchOperations.checkout(git.getRepository(), original, false,
                new NullProgressMonitor()).succeeded());
        }
    }

    @Test
    public void checkoutConflictKeepsStash() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-checkout-conflict-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(directory.toFile()).call())
        {
            commit(git, directory, "base\n"); //$NON-NLS-1$
            String original = git.getRepository().getBranch();
            git.checkout().setCreateBranch(true).setName("other").call(); //$NON-NLS-1$
            commit(git, directory, "other\n"); //$NON-NLS-1$
            git.checkout().setName(original).call();
            Files.writeString(directory.resolve("file.txt"), "local\n"); //$NON-NLS-1$ //$NON-NLS-2$
            OperationResult result = BranchOperations.checkout(git.getRepository(), "other", false, //$NON-NLS-1$
                new NullProgressMonitor());
            assertEquals(OperationResult.Kind.CONFLICT, result.kind());
            assertFalse(git.stashList().call().isEmpty());
        }
    }

    @Test
    public void remoteSelectionCreatesTrackingBranchAndRestoresDirtyTree() throws Exception
    {
        Path directory = Files.createTempDirectory("gitflow-remote-checkout-"); //$NON-NLS-1$
        Path bare = directory.resolve("origin.git"); //$NON-NLS-1$
        Path sourceDir = directory.resolve("source"); //$NON-NLS-1$
        Path work = directory.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git source = Git.init().setDirectory(sourceDir.toFile()).call())
        {
            commit(source, sourceDir, "base\n"); //$NON-NLS-1$
            source.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            source.checkout().setCreateBranch(true).setName("feature/topic").call(); //$NON-NLS-1$
            commit(source, sourceDir, "feature\n"); //$NON-NLS-1$
            source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            try (Git local = Git.cloneRepository().setURI(bare.toUri().toString())
                .setDirectory(work.toFile()).call())
            {
                Files.writeString(work.resolve("note.txt"), "dirty"); //$NON-NLS-1$ //$NON-NLS-2$
                OperationResult result = BranchOperations.checkout(local.getRepository(), "feature/topic", true, //$NON-NLS-1$
                    "refs/remotes/origin/feature/topic", new NullProgressMonitor()); //$NON-NLS-1$
                assertTrue(result.toString(), result.succeeded());
                assertEquals("feature/topic", local.getRepository().getBranch()); //$NON-NLS-1$
                assertEquals("origin", local.getRepository().getConfig().getString( //$NON-NLS-1$
                    "branch", "feature/topic", "remote")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertEquals("dirty", Files.readString(work.resolve("note.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue(local.stashList().call().isEmpty());
            }
        }
    }

    private static void commit(Git git, Path directory, String contents) throws Exception
    {
        Files.writeString(directory.resolve("file.txt"), contents); //$NON-NLS-1$
        git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
        git.commit().setMessage(contents).setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$
            .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
