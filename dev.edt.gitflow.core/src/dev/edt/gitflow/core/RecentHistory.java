package dev.edt.gitflow.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.revplot.PlotCommit;
import org.eclipse.jgit.revplot.PlotCommitList;
import org.eclipse.jgit.revplot.PlotLane;
import org.eclipse.jgit.revplot.PlotWalk;
import org.eclipse.jgit.revwalk.RevSort;

public final class RecentHistory
{
    public record Entry(PlotCommit<PlotLane> plot, String hash, String subject,
        String message, String author, int commitTime, List<String> labels)
    {
    }

    private RecentHistory()
    {
    }

    public static List<Entry> read(Repository repository, int limit) throws IOException
    {
        return read(repository, 0, limit);
    }

    public static List<Entry> read(Repository repository, int offset, int limit) throws IOException
    {
        if (offset < 0 || limit <= 0)
            return List.of();
        try (PlotWalk walk = new PlotWalk(repository))
        {
            walk.sort(RevSort.TOPO, true);
            List<Ref> refs = importantRefs(repository);
            walk.addAdditionalRefs(refs);
            ObjectId head = repository.resolve(Constants.HEAD);
            if (head == null)
                return List.of();
            walk.markStart(walk.parseCommit(head));
            PlotCommitList<PlotLane> commits = new PlotCommitList<>();
            commits.source(walk);
            commits.fillTo(offset + limit - 1);
            Map<String, List<String>> labelsByCommit = new HashMap<>();
            for (Ref ref : refs)
            {
                ObjectId id = repository.resolve(ref.getName() + "^{commit}"); //$NON-NLS-1$
                if (id != null)
                    labelsByCommit.computeIfAbsent(id.name(), key -> new ArrayList<>())
                        .add(Repository.shortenRefName(ref.getName()));
            }
            List<Entry> result = new ArrayList<>(Math.min(limit, Math.max(0, commits.size() - offset)));
            for (int i = offset; i < commits.size() && result.size() < limit; i++)
            {
                PlotCommit<PlotLane> commit = commits.get(i);
                walk.parseBody(commit);
                result.add(new Entry(commit, commit.name(), commit.getShortMessage(),
                    commit.getFullMessage().trim(), commit.getAuthorIdent().getName(),
                    commit.getCommitTime(), labelsByCommit.getOrDefault(commit.name(), List.of())));
            }
            return List.copyOf(result);
        }
    }

    public static String stateKey(Repository repository) throws IOException
    {
        List<String> refs = new ArrayList<>();
        refs.add("HEAD=" + repository.getFullBranch()); //$NON-NLS-1$
        for (Ref ref : historyRefs(repository))
        {
            ObjectId id = repository.resolve(ref.getName());
            refs.add(ref.getName() + "=" + (id == null ? "" : id.name())); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return String.join("\n", refs); //$NON-NLS-1$
    }

    private static List<Ref> historyRefs(Repository repository) throws IOException
    {
        List<Ref> refs = new ArrayList<>();
        repository.getRefDatabase().getRefs(RefDatabase.ALL).values().stream()
            .filter(ref -> ref.getName().startsWith(Constants.R_HEADS)
                || ref.getName().startsWith(Constants.R_REMOTES)
                || ref.getName().startsWith(Constants.R_TAGS))
            .sorted(java.util.Comparator.comparing(Ref::getName)).forEach(refs::add);
        Ref head = repository.exactRef(Constants.HEAD);
        if (head != null)
            refs.add(head);
        return refs;
    }

    private static List<Ref> importantRefs(Repository repository) throws IOException
    {
        List<Ref> refs = new ArrayList<>();
        String branch = repository.getFullBranch();
        if (branch != null && branch.startsWith(Constants.R_HEADS))
        {
            addRef(repository, refs, branch);
            String upstream = new BranchConfig(repository.getConfig(), repository.getBranch())
                .getRemoteTrackingBranch();
            if (upstream != null)
                addRef(repository, refs, upstream);
        }
        else
        {
            Ref head = repository.exactRef(Constants.HEAD);
            if (head != null)
                refs.add(head);
        }
        return refs;
    }

    private static void addRef(Repository repository, List<Ref> refs, String name) throws IOException
    {
        Ref ref = repository.exactRef(name);
        if (ref != null)
            refs.add(ref);
    }
}
