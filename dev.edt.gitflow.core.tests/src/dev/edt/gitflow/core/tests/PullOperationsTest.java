package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

import dev.edt.gitflow.core.OperationResult;
import dev.edt.gitflow.core.PullOperations;

public class PullOperationsTest
{
    @Test
    public void pullWithoutRemoteBranchKeepsWorktreeAndConfiguration() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-track-missing-"); //$NON-NLS-1$
        Path bare = root.resolve("origin.git"); //$NON-NLS-1$
        Path work = root.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git local = Git.init().setDirectory(work.toFile()).call())
        {
            commit(local, work, "file.txt", "base"); //$NON-NLS-1$ //$NON-NLS-2$
            local.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            Files.writeString(work.resolve("file.txt"), "dirty"); //$NON-NLS-1$ //$NON-NLS-2$
            OperationResult result = PullOperations.smartPull(local.getRepository(), true,
                new NullProgressMonitor());
            assertEquals(OperationResult.Kind.ERROR, result.kind());
            assertEquals("dirty", Files.readString(work.resolve("file.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull(local.getRepository().getConfig().getString("branch", //$NON-NLS-1$
                local.getRepository().getBranch(), "remote")); //$NON-NLS-1$
            assertTrue(local.stashList().call().isEmpty());
        }
    }

    @Test
    public void pullCanTrackExistingRemoteWithoutPushing() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-track-pull-"); //$NON-NLS-1$
        Path bare = root.resolve("origin.git"); //$NON-NLS-1$
        Path seed = root.resolve("seed"); //$NON-NLS-1$
        Path work = root.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git source = Git.init().setDirectory(seed.toFile()).call())
        {
            commit(source, seed, "base.txt", "base"); //$NON-NLS-1$ //$NON-NLS-2$
            source.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            try (Git local = Git.cloneRepository().setURI(bare.toUri().toString())
                .setDirectory(work.toFile()).call())
            {
                String branch = local.getRepository().getBranch();
                local.getRepository().getConfig().unset("branch", branch, "remote"); //$NON-NLS-1$ //$NON-NLS-2$
                local.getRepository().getConfig().unset("branch", branch, "merge"); //$NON-NLS-1$ //$NON-NLS-2$
                local.getRepository().getConfig().save();
                Files.writeString(work.resolve("local.txt"), "dirty"); //$NON-NLS-1$ //$NON-NLS-2$
                commit(source, seed, "remote.txt", "remote"); //$NON-NLS-1$ //$NON-NLS-2$
                source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
                OperationResult result = PullOperations.smartPull(local.getRepository(),
                    new NullProgressMonitor());
                assertTrue(result.toString(), result.succeeded());
                assertTrue(result.workspaceChanged());
                assertTrue(Files.exists(work.resolve("remote.txt"))); //$NON-NLS-1$
                assertEquals("dirty", Files.readString(work.resolve("local.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(source.getRepository().resolve("HEAD"), //$NON-NLS-1$
                    origin.getRepository().resolve("refs/heads/" + branch)); //$NON-NLS-1$
                assertEquals("origin", local.getRepository().getConfig().getString( //$NON-NLS-1$
                    "branch", branch, "remote")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
    }

    @Test
    public void firstSmartPushPublishesBranchWithoutConfirmation() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-publish-"); //$NON-NLS-1$
        Path bare = root.resolve("origin.git"); //$NON-NLS-1$
        Path work = root.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git local = Git.init().setDirectory(work.toFile()).call())
        {
            commit(local, work, "file.txt", "local"); //$NON-NLS-1$ //$NON-NLS-2$
            local.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            String branch = local.getRepository().getBranch();
            assertNull(origin.getRepository().resolve("refs/heads/" + branch)); //$NON-NLS-1$
            var originalHead = local.getRepository().resolve("HEAD"); //$NON-NLS-1$
            OperationResult result = PullOperations.smartPush(local.getRepository(), new NullProgressMonitor());
            assertTrue(result.toString(), result.succeeded());
            assertFalse(result.commitCreated());
            assertEquals(originalHead, local.getRepository().resolve("HEAD")); //$NON-NLS-1$
            assertEquals(local.getRepository().resolve("HEAD"), //$NON-NLS-1$
                origin.getRepository().resolve("refs/heads/" + branch)); //$NON-NLS-1$
            assertEquals("origin", local.getRepository().getConfig().getString( //$NON-NLS-1$
                "branch", branch, "remote")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    @Test
    public void firstSmartPushTracksExistingRemoteBeforeSending() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-track-existing-"); //$NON-NLS-1$
        Path bare = root.resolve("origin.git"); //$NON-NLS-1$
        Path seed = root.resolve("seed"); //$NON-NLS-1$
        Path work = root.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git source = Git.init().setDirectory(seed.toFile()).call())
        {
            commit(source, seed, "base.txt", "base"); //$NON-NLS-1$ //$NON-NLS-2$
            source.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            try (Git local = Git.cloneRepository().setURI(bare.toUri().toString())
                .setDirectory(work.toFile()).call())
            {
                String branch = local.getRepository().getBranch();
                local.getRepository().getConfig().unset("branch", branch, "remote"); //$NON-NLS-1$ //$NON-NLS-2$
                local.getRepository().getConfig().unset("branch", branch, "merge"); //$NON-NLS-1$ //$NON-NLS-2$
                local.getRepository().getConfig().save();
                commit(local, work, "local.txt", "local"); //$NON-NLS-1$ //$NON-NLS-2$
                commit(source, seed, "remote.txt", "remote"); //$NON-NLS-1$ //$NON-NLS-2$
                source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
                OperationResult result = PullOperations.smartPush(local.getRepository(), true,
                    new NullProgressMonitor());
                assertTrue(result.toString(), result.succeeded());
                assertTrue(result.workspaceChanged());
                assertEquals(local.getRepository().resolve("HEAD"), //$NON-NLS-1$
                    origin.getRepository().resolve("refs/heads/" + branch)); //$NON-NLS-1$
                assertTrue(Files.exists(work.resolve("remote.txt"))); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void missingUpstreamDoesNotTouchLocalChanges() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-no-upstream-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            commit(git, work, "file.txt", "base"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(work.resolve("file.txt"), "dirty"); //$NON-NLS-1$ //$NON-NLS-2$
            OperationResult result = PullOperations.smartPull(git.getRepository(), new NullProgressMonitor());
            assertEquals(OperationResult.Kind.ERROR, result.kind());
            assertEquals("dirty", Files.readString(work.resolve("file.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(git.stashList().call().isEmpty());
        }
    }

    @Test
    public void pullsCommitAndRestoresLocalChanges() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-pull-"); //$NON-NLS-1$
        Path bare = root.resolve("origin.git"); //$NON-NLS-1$
        Path seed = root.resolve("seed"); //$NON-NLS-1$
        Path work = root.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git source = Git.init().setDirectory(seed.toFile()).call())
        {
            commit(source, seed, "base.txt", "base"); //$NON-NLS-1$ //$NON-NLS-2$
            source.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            try (Git local = Git.cloneRepository().setURI(bare.toUri().toString())
                .setDirectory(work.toFile()).call())
            {
                Files.writeString(work.resolve("local.txt"), "local"); //$NON-NLS-1$ //$NON-NLS-2$
                commit(source, seed, "remote.txt", "remote"); //$NON-NLS-1$ //$NON-NLS-2$
                source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
                OperationResult result = PullOperations.smartPull(local.getRepository(), new NullProgressMonitor());
                assertTrue(result.toString(), result.succeeded());
                assertTrue(result.workspaceChanged());
                assertTrue(Files.exists(work.resolve("remote.txt"))); //$NON-NLS-1$
                assertTrue(Files.exists(work.resolve("local.txt"))); //$NON-NLS-1$
                assertTrue(local.stashList().call().isEmpty());
            }
        }
    }

    @Test
    public void stopsBeforeStashingWhenDirtyFileConflictsWithUpstream() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-pull-conflict-"); //$NON-NLS-1$
        Path bare = root.resolve("origin.git"); //$NON-NLS-1$
        Path seed = root.resolve("seed"); //$NON-NLS-1$
        Path work = root.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git source = Git.init().setDirectory(seed.toFile()).call())
        {
            commit(source, seed, "file.txt", "base"); //$NON-NLS-1$ //$NON-NLS-2$
            source.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            try (Git local = Git.cloneRepository().setURI(bare.toUri().toString())
                .setDirectory(work.toFile()).call())
            {
                Files.writeString(work.resolve("file.txt"), "local"); //$NON-NLS-1$ //$NON-NLS-2$
                var head = local.getRepository().resolve("HEAD"); //$NON-NLS-1$
                commit(source, seed, "file.txt", "remote"); //$NON-NLS-1$ //$NON-NLS-2$
                source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
                OperationResult result = PullOperations.smartPull(local.getRepository(), new NullProgressMonitor());
                assertEquals(OperationResult.Kind.CONFLICT, result.kind());
                assertFalse(result.workspaceChanged());
                assertEquals(head, local.getRepository().resolve("HEAD")); //$NON-NLS-1$
                assertEquals("local", Files.readString(work.resolve("file.txt"))); //$NON-NLS-1$
                assertTrue(local.stashList().call().isEmpty());
            }
        }
    }

    @Test
    public void smartPushChecksRemoteThenPushes() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-push-"); //$NON-NLS-1$
        Path bare = root.resolve("origin.git"); //$NON-NLS-1$
        Path seed = root.resolve("seed"); //$NON-NLS-1$
        Path work = root.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git source = Git.init().setDirectory(seed.toFile()).call())
        {
            commit(source, seed, "file.txt", "base"); //$NON-NLS-1$ //$NON-NLS-2$
            source.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            try (Git local = Git.cloneRepository().setURI(bare.toUri().toString())
                .setDirectory(work.toFile()).call())
            {
                commit(local, work, "local.txt", "local"); //$NON-NLS-1$ //$NON-NLS-2$
                OperationResult result = PullOperations.smartPush(local.getRepository(), new NullProgressMonitor());
                assertTrue(result.toString(), result.succeeded());
                assertFalse(result.workspaceChanged());
                assertEquals(origin.getRepository().resolve("HEAD"), local.getRepository().resolve("HEAD")); //$NON-NLS-1$ //$NON-NLS-2$
                OperationResult alreadySynced = PullOperations.smartPush(local.getRepository(),
                    new NullProgressMonitor());
                assertEquals(OperationResult.Kind.NO_CHANGE, alreadySynced.kind());
                assertFalse(alreadySynced.workspaceChanged());

                var remoteHead = origin.getRepository().resolve("HEAD"); //$NON-NLS-1$
                commit(local, work, "later.txt", "later"); //$NON-NLS-1$ //$NON-NLS-2$
                NullProgressMonitor cancelledAfterFetch = new NullProgressMonitor()
                {
                    @Override
                    public void worked(int work)
                    {
                        setCanceled(true);
                    }
                };
                OperationResult cancelled = PullOperations.smartPush(local.getRepository(),
                    cancelledAfterFetch);
                assertEquals(OperationResult.Kind.CANCELLED, cancelled.kind());
                assertEquals(remoteHead, origin.getRepository().resolve("HEAD")); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void smartPushRequestsNativeMergeWhenBothSidesChangedSameFile() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-same-file-sync-"); //$NON-NLS-1$
        Path bare = root.resolve("origin.git"); //$NON-NLS-1$
        Path seed = root.resolve("seed"); //$NON-NLS-1$
        Path work = root.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git source = Git.init().setDirectory(seed.toFile()).call())
        {
            commit(source, seed, "module.bsl", "first\nsecond\nthird\nfourth\nfifth\nsixth\n"); //$NON-NLS-1$ //$NON-NLS-2$
            source.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            try (Git local = Git.cloneRepository().setURI(bare.toUri().toString())
                .setDirectory(work.toFile()).call())
            {
                commit(local, work, "module.bsl", "local first\nsecond\nthird\nfourth\nfifth\nsixth\n"); //$NON-NLS-1$ //$NON-NLS-2$
                Files.writeString(work.resolve("other.txt"), "keep"); //$NON-NLS-1$ //$NON-NLS-2$
                var localHead = local.getRepository().resolve("HEAD"); //$NON-NLS-1$
                commit(source, seed, "module.bsl", "first\nsecond\nthird\nfourth\nfifth\nremote sixth\n"); //$NON-NLS-1$ //$NON-NLS-2$
                source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$

                OperationResult result = PullOperations.smartPush(local.getRepository(), new NullProgressMonitor());

                assertEquals(OperationResult.Kind.NEEDS_NATIVE_MERGE, result.kind());
                assertFalse(result.workspaceChanged());
                assertEquals(localHead, local.getRepository().resolve("HEAD")); //$NON-NLS-1$
                assertEquals("local first\nsecond\nthird\nfourth\nfifth\nsixth\n", //$NON-NLS-1$
                    Files.readString(work.resolve("module.bsl")));
                assertEquals("keep", Files.readString(work.resolve("other.txt"))); //$NON-NLS-1$
                assertTrue(local.stashList().call().isEmpty());
                assertFalse(result.message().isBlank());
                assertEquals(source.getRepository().resolve("HEAD"), //$NON-NLS-1$
                    local.getRepository().resolve("refs/remotes/origin/" + local.getRepository().getBranch())); //$NON-NLS-1$
                assertEquals(source.getRepository().resolve("HEAD"), //$NON-NLS-1$
                    origin.getRepository().resolve("refs/heads/" + local.getRepository().getBranch())); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void smartPullStopsBeforeStashingWhenDirtyFileAlsoChangedUpstream() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-dirty-overlap-"); //$NON-NLS-1$
        Path bare = root.resolve("origin.git"); //$NON-NLS-1$
        Path seed = root.resolve("seed"); //$NON-NLS-1$
        Path work = root.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git source = Git.init().setDirectory(seed.toFile()).call())
        {
            commit(source, seed, "module.bsl", "first\nsecond\nthird\nfourth\nfifth\nsixth\n"); //$NON-NLS-1$ //$NON-NLS-2$
            source.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            try (Git local = Git.cloneRepository().setURI(bare.toUri().toString())
                .setDirectory(work.toFile()).call())
            {
                String dirty = "local first\nsecond\nthird\nfourth\nfifth\nsixth\n"; //$NON-NLS-1$
                Files.writeString(work.resolve("module.bsl"), dirty); //$NON-NLS-1$
                var head = local.getRepository().resolve("HEAD"); //$NON-NLS-1$
                commit(source, seed, "module.bsl", "first\nsecond\nthird\nfourth\nfifth\nremote sixth\n"); //$NON-NLS-1$ //$NON-NLS-2$
                source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$

                OperationResult result = PullOperations.smartPull(local.getRepository(), new NullProgressMonitor());

                assertEquals(OperationResult.Kind.CONFLICT, result.kind());
                assertFalse(result.workspaceChanged());
                assertEquals(head, local.getRepository().resolve("HEAD")); //$NON-NLS-1$
                assertEquals(dirty, Files.readString(work.resolve("module.bsl"))); //$NON-NLS-1$
                assertTrue(local.stashList().call().isEmpty());
            }
        }
    }

    @Test
    public void fetchUpdatesRemoteTrackingWithoutChangingHeadOrWorktree() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-fetch-only-"); //$NON-NLS-1$
        Path bare = root.resolve("origin.git"); //$NON-NLS-1$
        Path seed = root.resolve("seed"); //$NON-NLS-1$
        Path work = root.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git source = Git.init().setDirectory(seed.toFile()).call())
        {
            commit(source, seed, "base.txt", "base"); //$NON-NLS-1$ //$NON-NLS-2$
            source.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            try (Git local = Git.cloneRepository().setURI(bare.toUri().toString())
                .setDirectory(work.toFile()).call())
            {
                var head = local.getRepository().resolve("HEAD"); //$NON-NLS-1$
                Files.writeString(work.resolve("local.txt"), "keep"); //$NON-NLS-1$ //$NON-NLS-2$
                commit(source, seed, "remote.txt", "remote"); //$NON-NLS-1$ //$NON-NLS-2$
                source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$

                OperationResult result = PullOperations.fetch(local.getRepository(), new NullProgressMonitor());

                assertTrue(result.toString(), result.succeeded());
                assertFalse(result.workspaceChanged());
                assertEquals(head, local.getRepository().resolve("HEAD")); //$NON-NLS-1$
                assertEquals("keep", Files.readString(work.resolve("local.txt"))); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(source.getRepository().resolve("HEAD"), //$NON-NLS-1$
                    local.getRepository().resolve("refs/remotes/origin/" + local.getRepository().getBranch())); //$NON-NLS-1$
            }
        }
    }

    private static void commit(Git git, Path directory, String file, String contents) throws Exception
    {
        Files.writeString(directory.resolve(file), contents);
        git.add().addFilepattern(file).call();
        git.commit().setMessage(file).setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$
            .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
