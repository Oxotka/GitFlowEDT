package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
                assertTrue(Files.exists(work.resolve("remote.txt"))); //$NON-NLS-1$
                assertTrue(Files.exists(work.resolve("local.txt"))); //$NON-NLS-1$
                assertTrue(local.stashList().call().isEmpty());
            }
        }
    }

    @Test
    public void keepsStashWhenRestoringAfterPullConflicts() throws Exception
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
                commit(source, seed, "file.txt", "remote"); //$NON-NLS-1$ //$NON-NLS-2$
                source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
                OperationResult result = PullOperations.smartPull(local.getRepository(), new NullProgressMonitor());
                assertEquals(OperationResult.Kind.CONFLICT, result.kind());
                assertFalse(local.stashList().call().isEmpty());
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
                assertEquals(origin.getRepository().resolve("HEAD"), local.getRepository().resolve("HEAD")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(OperationResult.Kind.NO_CHANGE,
                    PullOperations.smartPush(local.getRepository(), new NullProgressMonitor()).kind());
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
