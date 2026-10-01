package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

import dev.edt.gitflow.core.CommitOperations;
import dev.edt.gitflow.core.OperationResult;

public class CommitOperationsTest
{
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
            assertEquals(git.getRepository().resolve("HEAD"), //$NON-NLS-1$
                origin.getRepository().resolve(git.getRepository().getFullBranch()));
            assertTrue(git.status().call().getUntracked().contains("untracked.txt")); //$NON-NLS-1$
        }
    }

    @Test
    public void generatesTaskPrefixFromBranch()
    {
        assertEquals("JIRA-1234: ", CommitOperations.generateMessage("feature/JIRA-1234-dogovora")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(CommitOperations.generateMessage("feature/no-key")); //$NON-NLS-1$
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
}
