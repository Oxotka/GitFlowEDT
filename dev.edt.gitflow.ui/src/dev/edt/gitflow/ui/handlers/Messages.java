package dev.edt.gitflow.ui.handlers;

import java.util.ResourceBundle;

public final class Messages
{
    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle(
        "dev.edt.gitflow.ui.handlers.messages"); //$NON-NLS-1$

    private Messages()
    {
    }

    public static String get(String key)
    {
        return BUNDLE.getString(key);
    }

    public static String getOrDefault(String key, String fallback)
    {
        return BUNDLE.containsKey(key) ? BUNDLE.getString(key) : BUNDLE.getString(fallback);
    }
}
