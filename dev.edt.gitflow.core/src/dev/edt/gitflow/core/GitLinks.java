package dev.edt.gitflow.core;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

public final class GitLinks
{
    public enum Target { FILE, BRANCH, COMMIT }

    private GitLinks()
    {
    }

    public static String link(Repository repository, Target target, String path) throws IOException
    {
        String remote = repository.getConfig().getString("remote", "origin", "url"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (remote == null)
            throw new IllegalArgumentException("У репозитория не настроен remote.origin.url."); //$NON-NLS-1$
        ObjectId head = repository.resolve("HEAD"); //$NON-NLS-1$
        if (head == null)
            throw new IllegalArgumentException("В репозитории пока нет коммитов."); //$NON-NLS-1$
        if (target == Target.FILE)
        {
            if (path == null || path.isBlank())
                throw new IllegalArgumentException("Выделите отслеживаемый Git-файл."); //$NON-NLS-1$
            try (RevWalk walk = new RevWalk(repository))
            {
                RevCommit commit = walk.parseCommit(head);
                try (TreeWalk found = TreeWalk.forPath(repository, path, commit.getTree()))
                {
                    if (found == null)
                        throw new IllegalArgumentException("Файл не отслеживается в последнем коммите."); //$NON-NLS-1$
                }
            }
        }
        String ref = target == Target.BRANCH ? repository.getBranch() : head.name();
        return fromRemote(remote, target, ref, path);
    }

    public static String fromRemote(String remote, Target target, String ref, String path)
    {
        String base = baseUrl(remote);
        String host = URI.create(base).getHost().toLowerCase();
        String encodedRef = encode(ref);
        if (host.contains("github")) //$NON-NLS-1$
        {
            return switch (target)
            {
                case FILE -> base + "/blob/" + encodedRef + "/" + encodePath(path); //$NON-NLS-1$ //$NON-NLS-2$
                case BRANCH -> base + "/tree/" + encodedRef; //$NON-NLS-1$
                case COMMIT -> base + "/commit/" + encodedRef; //$NON-NLS-1$
            };
        }
        if (host.contains("bitbucket")) //$NON-NLS-1$
        {
            return switch (target)
            {
                case FILE -> base + "/src/" + encodedRef + "/" + encodePath(path); //$NON-NLS-1$ //$NON-NLS-2$
                case BRANCH -> base + "/src/" + encodedRef; //$NON-NLS-1$
                case COMMIT -> base + "/commits/" + encodedRef; //$NON-NLS-1$
            };
        }
        // Unknown hosts use GitLab URLs, including self-hosted GitLab instances.
        return switch (target)
        {
            case FILE -> base + "/-/blob/" + encodedRef + "/" + encodePath(path); //$NON-NLS-1$ //$NON-NLS-2$
            case BRANCH -> base + "/-/tree/" + encodedRef; //$NON-NLS-1$
            case COMMIT -> base + "/-/commit/" + encodedRef; //$NON-NLS-1$
        };
    }

    private static String baseUrl(String remote)
    {
        String url = remote;
        if (remote.matches("^[^@]+@[^:]+:.+$")) //$NON-NLS-1$
        {
            int at = remote.indexOf('@');
            int colon = remote.indexOf(':', at);
            url = "https://" + remote.substring(at + 1, colon) + "/" + remote.substring(colon + 1); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (remote.startsWith("ssh://")) //$NON-NLS-1$
        {
            try
            {
                URI ssh = new URI(remote);
                url = "https://" + ssh.getHost() + ssh.getRawPath(); //$NON-NLS-1$
            }
            catch (URISyntaxException e)
            {
                throw new IllegalArgumentException("Некорректный адрес remote.origin.url.", e); //$NON-NLS-1$
            }
        }
        URI uri;
        try
        {
            uri = new URI(url);
        }
        catch (URISyntaxException e)
        {
            throw new IllegalArgumentException("Некорректный адрес remote.origin.url.", e); //$NON-NLS-1$
        }
        if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) //$NON-NLS-1$ //$NON-NLS-2$
            throw new IllegalArgumentException("Для remote.origin.url нельзя построить ссылку на сайт."); //$NON-NLS-1$
        String path = uri.getRawPath();
        if (path.endsWith(".git")) //$NON-NLS-1$
            path = path.substring(0, path.length() - 4);
        while (path.endsWith("/")) //$NON-NLS-1$
            path = path.substring(0, path.length() - 1);
        return uri.getScheme() + "://" + uri.getRawAuthority() + path; //$NON-NLS-1$
    }

    private static String encodePath(String path)
    {
        String[] parts = path.replace('\\', '/').split("/", -1); //$NON-NLS-1$
        StringBuilder encoded = new StringBuilder();
        for (String part : parts)
        {
            if (!encoded.isEmpty())
                encoded.append('/');
            encoded.append(encode(part));
        }
        return encoded.toString();
    }

    private static String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
