package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.Test;

import dev.edt.gitflow.core.GitLinks;
import dev.edt.gitflow.core.GitLinks.Target;

public class GitLinksTest
{
    @Test
    public void encodesSelfHostedGitLabUrls()
    {
        assertEquals("https://git.company.ru/group/project/-/blob/abc123/src/%D0%9C%D0%BE%D0%B4%D1%83%D0%BB%D1%8C.bsl", //$NON-NLS-1$
            GitLinks.fromRemote("git@git.company.ru:group/project.git", Target.FILE, "abc123", //$NON-NLS-1$ //$NON-NLS-2$
                "src/Модуль.bsl")); //$NON-NLS-1$
        assertEquals("https://git.company.ru/group/project/-/tree/feature%2FJIRA-1234", //$NON-NLS-1$
            GitLinks.fromRemote("git@git.company.ru:group/project.git", Target.BRANCH, //$NON-NLS-1$
                "feature/JIRA-1234", null)); //$NON-NLS-1$
    }

    @Test
    public void buildsGithubAndBitbucketUrls()
    {
        assertEquals("https://github.com/org/repo/commit/abc123", //$NON-NLS-1$
            GitLinks.fromRemote("https://github.com/org/repo.git", Target.COMMIT, "abc123", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("https://bitbucket.org/org/repo/src/abc123/path/file.txt", //$NON-NLS-1$
            GitLinks.fromRemote("https://bitbucket.org/org/repo.git", Target.FILE, "abc123", //$NON-NLS-1$ //$NON-NLS-2$
                "path/file.txt")); //$NON-NLS-1$
    }

    @Test
    public void detectsHostingProviderForLabels()
    {
        assertEquals("GitHub", GitLinks.providerName("git@github.com:org/repo.git")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("GitLab", GitLinks.providerName("https://gitlab.example.com/org/repo.git")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Bitbucket", GitLinks.providerName("https://bitbucket.org/org/repo.git")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(GitLinks.providerName("https://git.company.ru/org/repo.git")); //$NON-NLS-1$
    }

    @Test
    public void fileLinkUsesCurrentBranchInsteadOfCommitId() throws Exception
    {
        Path work = Files.createTempDirectory("gitflow-file-link-"); //$NON-NLS-1$
        try (Git git = Git.init().setDirectory(work.toFile()).call())
        {
            Files.writeString(work.resolve("file.txt"), "content"); //$NON-NLS-1$ //$NON-NLS-2$
            git.add().addFilepattern("file.txt").call(); //$NON-NLS-1$
            git.commit().setMessage("base").setAuthor("Test", "test@example.org") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .setCommitter("Test", "test@example.org").call(); //$NON-NLS-1$ //$NON-NLS-2$
            git.getRepository().getConfig().setString("remote", "origin", "url", //$NON-NLS-1$ //$NON-NLS-2$
                "https://github.com/org/repo.git"); //$NON-NLS-1$
            git.getRepository().getConfig().save();

            String branch = git.getRepository().getBranch();
            assertEquals(GitLinks.fromRemote("https://github.com/org/repo.git", Target.FILE, branch, //$NON-NLS-1$
                "file.txt"), GitLinks.link(git.getRepository(), Target.FILE, "file.txt")); //$NON-NLS-1$
        }
    }
}
