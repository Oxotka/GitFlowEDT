package dev.edt.gitflow.core.internal;

import org.eclipse.core.runtime.Plugin;
import org.osgi.framework.BundleContext;

/**
 * Активатор core-бандла. Логика операций (Smart Pull, стеши и т.д.)
 * добавляется в фазе 1 — см. SPEC.md §5, §6.
 */
public class CorePlugin extends Plugin
{
    public static final String PLUGIN_ID = "dev.edt.gitflow.core"; //$NON-NLS-1$

    private static CorePlugin instance;

    @Override
    public void start(BundleContext context) throws Exception
    {
        super.start(context);
        instance = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception
    {
        instance = null;
        super.stop(context);
    }

    public static CorePlugin getInstance()
    {
        return instance;
    }
}
