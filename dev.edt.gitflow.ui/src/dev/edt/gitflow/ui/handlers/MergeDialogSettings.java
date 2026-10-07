package dev.edt.gitflow.ui.handlers;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.osgi.service.prefs.BackingStoreException;

final class MergeDialogSettings
{
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$

    private MergeDialogSettings()
    {
    }

    static void attach(Composite area)
    {
        var preferences = InstanceScope.INSTANCE.getNode(PLUGIN_ID).node("mergeDialog"); //$NON-NLS-1$
        List<Button> buttons = new ArrayList<>();
        List<Text> files = new ArrayList<>();
        collect(area, false, buttons, files);
        // Restore every radio state before notifying the native selection handlers.
        for (int i = 0; i < buttons.size(); i++)
        {
            Button button = buttons.get(i);
            String key = "button." + i; //$NON-NLS-1$
            if (preferences.get(key, null) != null)
                button.setSelection(preferences.getBoolean(key, button.getSelection()));
        }
        for (int i = 0; i < buttons.size(); i++)
        {
            Button button = buttons.get(i);
            if (preferences.get("button." + i, null) != null //$NON-NLS-1$
                && ((button.getStyle() & SWT.RADIO) == 0 || button.getSelection()))
                button.notifyListeners(SWT.Selection, new Event());
        }
        for (int i = 0; i < files.size(); i++)
        {
            Text text = files.get(i);
            String saved = preferences.get("file." + i, null); //$NON-NLS-1$
            if (saved != null)
                text.setText(saved);
        }
        Runnable save = () ->
        {
            for (int i = 0; i < buttons.size(); i++)
                preferences.putBoolean("button." + i, buttons.get(i).getSelection()); //$NON-NLS-1$
            for (int i = 0; i < files.size(); i++)
                preferences.put("file." + i, files.get(i).getText()); //$NON-NLS-1$
            try
            {
                preferences.flush();
            }
            catch (BackingStoreException e)
            {
                Platform.getLog(Platform.getBundle(PLUGIN_ID)).log(
                    new Status(org.eclipse.core.runtime.IStatus.WARNING, PLUGIN_ID,
                        "Не удалось сохранить параметры слияния", e)); //$NON-NLS-1$
            }
        };
        for (Button button : buttons)
            button.addListener(SWT.Selection, event -> save.run());
        for (Text text : files)
            text.addModifyListener(event -> save.run());
    }

    private static void collect(Composite parent, boolean settingsFile, List<Button> buttons, List<Text> files)
    {
        boolean fileControl = settingsFile || parent.getClass().getSimpleName()
            .equals("MergeSettingsFileSelectionControl"); //$NON-NLS-1$
        for (Control child : parent.getChildren())
        {
            if (child instanceof Button button && (button.getStyle() & (SWT.RADIO | SWT.CHECK)) != 0)
                buttons.add(button);
            else if (child instanceof Text text && fileControl)
                files.add(text);
            else if (child instanceof Composite composite)
                collect(composite, fileControl, buttons, files);
        }
    }
}
