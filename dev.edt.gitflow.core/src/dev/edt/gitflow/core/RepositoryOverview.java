package dev.edt.gitflow.core;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.RevWalkUtils;

public record RepositoryOverview(int changedFiles, int incoming, int outgoing)
{
    public static RepositoryOverview read(Repository repository) throws GitAPIException, IOException
    {
        var status = Git.wrap(repository).status().call();
        Set<String> changed = new HashSet<>(status.getUncommittedChanges());
        changed.addAll(status.getUntracked());

        String upstream = new BranchConfig(repository.getConfig(), repository.getBranch())
            .getRemoteTrackingBranch();
        ObjectId local = repository.resolve("HEAD"); //$NON-NLS-1$
        ObjectId remote = upstream == null ? null : repository.resolve(upstream);
        if (local == null || remote == null)
            return new RepositoryOverview(changed.size(), -1, -1);

        try (RevWalk walk = new RevWalk(repository))
        {
            int incoming = RevWalkUtils.count(walk, walk.parseCommit(remote), walk.parseCommit(local));
            walk.reset();
            int outgoing = RevWalkUtils.count(walk, walk.parseCommit(local), walk.parseCommit(remote));
            return new RepositoryOverview(changed.size(), incoming, outgoing);
        }
    }
}
