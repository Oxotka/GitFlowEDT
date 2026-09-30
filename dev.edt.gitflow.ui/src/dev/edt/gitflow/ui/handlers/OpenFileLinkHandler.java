package dev.edt.gitflow.ui.handlers;

import dev.edt.gitflow.core.GitLinks;

public class OpenFileLinkHandler extends OpenLinkHandler
{
    @Override
    protected GitLinks.Target target()
    {
        return GitLinks.Target.FILE;
    }
}
