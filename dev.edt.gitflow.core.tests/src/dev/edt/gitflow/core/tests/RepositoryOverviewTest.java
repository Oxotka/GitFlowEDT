package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;

import dev.edt.gitflow.core.RepositoryOverview;
import dev.edt.gitflow.core.WorkingChanges;

public class RepositoryOverviewTest
{
    @Test
    public void countsChangedFilesAndDivergedCommits() throws Exception
    {
        Path root = Files.createTempDirectory("gitflow-overview-"); //$NON-NLS-1$
        Path bare = root.resolve("origin.git"); //$NON-NLS-1$
        Path seed = root.resolve("seed"); //$NON-NLS-1$
        Path work = root.resolve("work"); //$NON-NLS-1$
        try (Git origin = Git.init().setBare(true).setDirectory(bare.toFile()).call();
             Git source = Git.init().setDirectory(seed.toFile()).call())
        {
            commit(source, seed, "tracked.txt", "base"); //$NON-NLS-1$ //$NON-NLS-2$
            source.remoteAdd().setName("origin").setUri(new URIish(bare.toUri().toString())).call(); //$NON-NLS-1$
            source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
            try (Git local = Git.cloneRepository().setURI(bare.toUri().toString())
                .setDirectory(work.toFile()).call())
            {
                commit(local, work, "local.txt", "local"); //$NON-NLS-1$ //$NON-NLS-2$
                commit(source, seed, "remote.txt", "remote"); //$NON-NLS-1$ //$NON-NLS-2$
                source.push().setRemote("origin").add(source.getRepository().getFullBranch()).call(); //$NON-NLS-1$
                local.fetch().setRemote("origin").call(); //$NON-NLS-1$
                Files.writeString(work.resolve("tracked.txt"), "changed"); //$NON-NLS-1$ //$NON-NLS-2$
                Files.writeString(work.resolve("new.txt"), "untracked"); //$NON-NLS-1$ //$NON-NLS-2$

                assertEquals(new RepositoryOverview(2, 1, 1),
                    RepositoryOverview.read(local.getRepository()));
                assertEquals(RepositoryOverview.read(local.getRepository()),
                    RepositoryOverview.read(local.getRepository(),
                        WorkingChanges.read(local.getRepository())));
                local.add().addFilepattern("tracked.txt").call(); //$NON-NLS-1$
                Files.writeString(work.resolve("tracked.txt"), "changed again"); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(new RepositoryOverview(2, 1, 1),
                    RepositoryOverview.read(local.getRepository(),
                        WorkingChanges.read(local.getRepository())));
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
