package dev.edt.gitflow.ui.handlers;

import dev.edt.gitflow.core.GitLinks;

public class OpenBranchLinkHandler extends OpenLinkHandler
{
    @Override
    protected GitLinks.Target target()
    {
        return GitLinks.Target.BRANCH;
    }
}
