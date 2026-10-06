package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

import dev.edt.gitflow.core.CommitOperations;
import dev.edt.gitflow.core.OperationResult;

public class CommitOperationsTest
{
    @Test
    public void commitsOnlyIndexAndSupportsFirstCommit() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-index-commit-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            Files.writeString(work.resolve("file.txt"), "first"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            assertEquals(java.util.Set.of("file.txt"), CommitOperations.stagedPaths(git.getRepository())); //$NON-NLS-1$
            assertTrue(CommitOperations.safeCommit(git.getRepository(), "first", false, true, //$NON-NLS-1$
                new NullProgressMonitor()).succeeded());
            Files.writeString(work.resolve("file.txt"), "prepared"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            Files.writeString(work.resolve("file.txt"), "leave unstaged"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("new.txt"), "keep out"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(CommitOperations.safeCommit(git.getRepository(), "prepared", false, true, //$NON-NLS-1$
                new NullProgressMonitor()).succeeded());
            assertTrue(CommitOperations.stagedPaths(git.getRepository()).isEmpty());
            assertEquals("prepared", new String(git.getRepository().open( //$NON-NLS-1$
                git.getRepository().resolve("HEAD:file.txt")).getBytes(), java.nio.charset.StandardCharsets.UTF_8)); //$NON-NLS-1$
            assertTrue(git.status().call().getModified().contains("file.txt")); //$NON-NLS-1$
            assertTrue(git.status().call().getUntracked().contains("new.txt")); //$NON-NLS-1$
            assertEquals(dev.edt.gitflow.core.OperationResult.Kind.NO_CHANGE,
                CommitOperations.safeCommit(git.getRepository(), "empty", false, true, //$NON-NLS-1$
                    new NullProgressMonitor()).kind());
            assertEquals("prepared", git.log().setMaxCount(1).call().iterator().next().getShortMessage()); //$NON-NLS-1$
        }
    }

    @Test
    public void commitAndPushPublishesWithoutIncludingUntrackedFile() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-commit-push-"); //$NON-NLS-1$
        Path bare = root.resolve("origin.git"); //$NON-NLS-1$
        Path work = root.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git git = Git.init().setDirectory(work.toFile()).call())
        {
            Files.writeString(work.resolve("file.txt"), "base"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("base").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            git.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            Files.writeString(work.resolve("file.txt"), "changed"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("untracked.txt"), "leave out"); //$NON-NLS-1$ //$NON-NLS-2$
            var result = CommitOperations.commitAndPush(git.getRepository(), "fix", true, true, true, //$NON-NLS-1$
                new NullProgressMonitor());
            assertTrue(result.toString(), result.succeeded());
            assertTrue(result.commitCreated());
            assertEquals(git.getRepository().resolve("HEAD"), //$NON-NLS-1$
                origin.getRepository().resolve(git.getRepository().getFullBranch()));
            assertTrue(git.status().call().getUntracked().contains("untracked.txt")); //$NON-NLS-1$
        }
    }

    @Test
    public void cancellationAfterCommitKeepsCommitLocalWithoutPushing() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-cancel-push-"); //$NON-NLS-1$
        Path bare = root.resolve("origin.git"); //$NON-NLS-1$
        Path work = root.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git git = Git.init().setDirectory(work.toFile()).call())
        {
            Files.writeString(work.resolve("file.txt"), "base"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("base").call(); //$NON-NLS-1$
            git.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            git.push().setRemote("origin").add(git.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            String branch = git.getRepository().getBranch();
            git.getRepository().getConfig().setString("branch", branch, "remote", "origin"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            git.getRepository().getConfig().setString("branch", branch, "merge", "refs/heads/" + branch); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            git.getRepository().getConfig().save();
            var remoteHead = origin.getRepository().resolve("refs/heads/" + branch); //$NON-NLS-1$
            Files.writeString(work.resolve("file.txt"), "local"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            NullProgressMonitor monitor = new NullProgressMonitor()
            {
                private int steps;

                @Override
                public void worked(int work)
                {
                    if ((steps += work) >= 2)
                        setCanceled(true);
                }
            };

            OperationResult result = CommitOperations.commitAndPush(git.getRepository(), "local", //$NON-NLS-1$
                false, true, true, monitor);

            assertEquals(OperationResult.Kind.CANCELLED, result.kind());
            assertTrue(result.commitCreated());
            assertEquals("local", git.log().setMaxCount(1).call().iterator().next().getShortMessage()); //$NON-NLS-1$
            assertEquals(remoteHead, origin.getRepository().resolve("refs/heads/" + branch)); //$NON-NLS-1$
        }
    }

    @Test
    public void commitAndPushKeepsCommitLocalWhenSameFileChangedUpstream() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-commit-overlap-"); //$NON-NLS-1$
        Path bare = root.resolve("origin.git"); //$NON-NLS-1$
        Path seed = root.resolve("seed"); //$NON-NLS-1$
        Path work = root.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git source = Git.init().setDirectory(seed.toFile()).call())
        {
            Files.writeString(seed.resolve("module.bsl"), "first\nsecond\nthird\nfourth\nfifth\nsixth\n"); //$NON-NLS-1$ //$NON-NLS-2$
            source.add().addFilepattern("module.bsl").call(); //$NON-NLS-1$
            source.commit().setMessage("base").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$
                .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            source.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            try (Git local = Git.cloneRepository().setURI(bare.toUri().toString())
                .setDirectory(work.toFile()).call())
            {
                Files.writeString(work.resolve("module.bsl"), //$NON-NLS-1$
                    "local first\nsecond\nthird\nfourth\nfifth\nsixth\n"); //$NON-NLS-1$
                Files.writeString(seed.resolve("module.bsl"), //$NON-NLS-1$
                    "first\nsecond\nthird\nfourth\nfifth\nremote sixth\n"); //$NON-NLS-1$
                source.add().addFilepattern("module.bsl").call(); //$NON-NLS-1$
                source.commit().setMessage("remote").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$
                    .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
                source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$

                OperationResult result = CommitOperations.commitAndPush(local.getRepository(), "local", //$NON-NLS-1$
                    true, true, true, new NullProgressMonitor());

                assertEquals(OperationResult.Kind.NEEDS_NATIVE_MERGE, result.kind());
                assertTrue(result.commitCreated());
                assertFalse(result.workspaceChanged());
                assertTrue(result.message().contains("Коммит сохранён локально")); //$NON-NLS-1$
                assertEquals("local", local.log().setMaxCount(1).call().iterator().next().getShortMessage()); //$NON-NLS-1$
                assertEquals(source.getRepository().resolve("HEAD"), //$NON-NLS-1$
                    origin.getRepository().resolve("refs/heads/" + local.getRepository().getBranch())); //$NON-NLS-1$
                assertEquals("local first\nsecond\nthird\nfourth\nfifth\nsixth\n", //$NON-NLS-1$
                    Files.readString(work.resolve("module.bsl")));
                assertTrue(local.stashList().call().isEmpty());
            }
        }
    }

    @Test
    public void acceptsPlainMessageAndProtectsMainBranch() throws Exception
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
            assertEquals(OperationResult.Kind.NEEDS_CONFIRMATION,
                CommitOperations.safeCommit(git.getRepository(), "fix", false, false, monitor).kind()); //$NON-NLS-1$
            assertEquals(OperationResult.Kind.SUCCESS,
                CommitOperations.safeCommit(git.getRepository(), "fix", false, true, monitor).kind()); //$NON-NLS-1$
            assertEquals("fix", git.log().setMaxCount(1).call().iterator().next().getShortMessage()); //$NON-NLS-1$
        }
    }

    @Test
    public void commitsResolvedMergeWithMultilineMessage() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-merge-commit-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(root.toFile()).call())
        {
            Files.writeString(root.resolve("file.txt"), "base\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("base").call(); //$NON-NLS-1$
            String original = git.getRepository().getBranch();
            git.branchCreate().setName("feature").call(); //$NON-NLS-1$
            git.checkout().setName("feature").call(); //$NON-NLS-1$
            Files.writeString(root.resolve("file.txt"), "feature\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("feature").call(); //$NON-NLS-1$
            git.checkout().setName(original).call();
            Files.writeString(root.resolve("file.txt"), "main\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("main").call(); //$NON-NLS-1$
            MergeResult merge = git.merge().include(git.getRepository().findRef("feature")).call(); //$NON-NLS-1$
            assertEquals(MergeResult.MergeStatus.CONFLICTING, merge.getMergeStatus());
            Files.writeString(root.resolve("file.txt"), "resolved\n"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            assertEquals(RepositoryState.MERGING_RESOLVED, git.getRepository().getRepositoryState());

            String message = "Merge feature\n\nReviewed changes"; //$NON-NLS-1$
            OperationResult result = CommitOperations.safeCommit(git.getRepository(), message, false,
                true, new NullProgressMonitor());
            assertEquals(result.toString(), OperationResult.Kind.SUCCESS, result.kind());
            var commit = git.log().setMaxCount(1).call().iterator().next();
            assertEquals(2, commit.getParentCount());
            assertEquals(message, commit.getFullMessage());
        }
    }
}
