package dev.edt.gitflow.ui.views;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import dev.edt.gitflow.ui.handlers.Messages;

public class GitFlowView extends ViewPart
{
    public static final String ID = "dev.edt.gitflow.ui.view.operations"; //$NON-NLS-1$
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$
    private static final StringBuilder HISTORY = new StringBuilder();
    private static GitFlowView instance;

    private Text output;

    @Override
    public void createPartControl(Composite parent)
    {
        instance = this;
        parent.setLayout(new GridLayout(1, false));
        new Label(parent, SWT.NONE).setText(Messages.get("viewIntro")); //$NON-NLS-1$
        output = new Text(parent, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.WRAP);
        output.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        output.setText(HISTORY.toString());
        Button clear = new Button(parent, SWT.PUSH);
        clear.setText(Messages.get("clearHistory")); //$NON-NLS-1$
        clear.addListener(SWT.Selection, event ->
        {
            HISTORY.setLength(0);
            output.setText(""); //$NON-NLS-1$
        });
    }

    @Override
    public void setFocus()
    {
        if (output != null && !output.isDisposed())
            output.setFocus();
    }

    @Override
    public void dispose()
    {
        instance = null;
        super.dispose();
    }

    public static void publish(String message)
    {
        Display display = Display.getDefault();
        if (display.getThread() != Thread.currentThread())
        {
            display.asyncExec(() -> publish(message));
            return;
        }
        HISTORY.append('[').append(TIME.format(LocalTime.now())).append("] ") //$NON-NLS-1$
            .append(message).append(System.lineSeparator());
        if (HISTORY.length() > 30000)
            HISTORY.delete(0, HISTORY.length() - 20000);
        if (instance == null)
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return;
            try
            {
                page.showView(ID, null, IWorkbenchPage.VIEW_VISIBLE);
            }
            catch (PartInitException e)
            {
                Platform.getLog(Platform.getBundle(PLUGIN_ID)).log(
                    new Status(IStatus.ERROR, PLUGIN_ID, e.getMessage(), e));
            }
        }
        if (instance != null && instance.output != null && !instance.output.isDisposed())
        {
            instance.output.setText(HISTORY.toString());
            instance.output.setSelection(instance.output.getCharCount());
        }
    }
}
