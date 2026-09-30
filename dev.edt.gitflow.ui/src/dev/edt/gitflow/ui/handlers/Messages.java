package dev.edt.gitflow.ui.handlers;

import java.util.ResourceBundle;

final class Messages
{
    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle(
        "dev.edt.gitflow.ui.handlers.messages"); //$NON-NLS-1$

    private Messages()
    {
    }

    static String get(String key)
    {
        return BUNDLE.getString(key);
    }
}
