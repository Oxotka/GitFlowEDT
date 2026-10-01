package dev.edt.gitflow.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revplot.PlotCommit;
import org.eclipse.jgit.revplot.PlotCommitList;
import org.eclipse.jgit.revplot.PlotLane;
import org.eclipse.jgit.revplot.PlotWalk;
import org.eclipse.jgit.revwalk.RevSort;

public final class RecentHistory
{
    public record Entry(PlotCommit<PlotLane> plot, String hash, String subject,
        String message, String author, int commitTime)
    {
    }

    private RecentHistory()
    {
    }

    public static List<Entry> read(Repository repository, int limit) throws IOException
    {
        ObjectId head = repository.resolve(Constants.HEAD);
        if (head == null || limit <= 0)
            return List.of();
        try (PlotWalk walk = new PlotWalk(repository))
        {
            walk.sort(RevSort.TOPO, true);
            walk.markStart(walk.parseCommit(head));
            PlotCommitList<PlotLane> commits = new PlotCommitList<>();
            commits.source(walk);
            commits.fillTo(limit - 1);
            List<Entry> result = new ArrayList<>(commits.size());
            for (PlotCommit<PlotLane> commit : commits)
            {
                walk.parseBody(commit);
                result.add(new Entry(commit, commit.name(), commit.getShortMessage(),
                    commit.getFullMessage().trim(), commit.getAuthorIdent().getName(),
                    commit.getCommitTime()));
            }
            return List.copyOf(result);
        }
    }
}
