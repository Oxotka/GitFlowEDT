package dev.edt.gitflow.core;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.Constants;
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
        return read(repository, changed.size());
    }

    public static RepositoryOverview read(Repository repository, WorkingChanges changes) throws IOException
    {
        return read(repository, changedFiles(changes));
    }

    public static RepositoryOverview read(Repository repository, WorkingChanges changes,
        RepositoryOverview previous, ObjectId previousHead, ObjectId previousUpstream) throws IOException
    {
        int changed = changedFiles(changes);
        if (previous != null && refsUnchanged(repository, previousHead, previousUpstream))
            return new RepositoryOverview(changed, previous.incoming(), previous.outgoing());
        return read(repository, changed);
    }

    public static boolean refsUnchanged(Repository repository, ObjectId head, ObjectId upstream) throws IOException
    {
        ObjectId currentHead = repository.resolve(Constants.HEAD);
        String tracking = new BranchConfig(repository.getConfig(), repository.getBranch()).getRemoteTrackingBranch();
        ObjectId currentUpstream = tracking == null ? null : repository.resolve(tracking);
        return Objects.equals(head, currentHead) && Objects.equals(upstream, currentUpstream);
    }

    private static int changedFiles(WorkingChanges changes)
    {
        return (int) Stream.concat(changes.staged().stream(), changes.unstaged().stream())
            .map(WorkingChanges.FileChange::path).distinct().count();
    }

    private static RepositoryOverview read(Repository repository, int changed) throws IOException
    {
        String upstream = new BranchConfig(repository.getConfig(), repository.getBranch())
            .getRemoteTrackingBranch();
        ObjectId local = repository.resolve("HEAD"); //$NON-NLS-1$
        ObjectId remote = upstream == null ? null : repository.resolve(upstream);
        if (local == null || remote == null)
            return new RepositoryOverview(changed, -1, -1);

        try (RevWalk walk = new RevWalk(repository))
        {
            int incoming = RevWalkUtils.count(walk, walk.parseCommit(remote), walk.parseCommit(local));
            walk.reset();
            int outgoing = RevWalkUtils.count(walk, walk.parseCommit(local), walk.parseCommit(remote));
            return new RepositoryOverview(changed, incoming, outgoing);
        }
    }
}
