package dev.edt.gitflow.core.tests;

import static org.junit.Assert.assertEquals;

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
}
