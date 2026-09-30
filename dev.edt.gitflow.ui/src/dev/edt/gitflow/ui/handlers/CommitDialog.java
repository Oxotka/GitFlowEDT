package dev.edt.gitflow.ui.handlers;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

final class CommitDialog extends TitleAreaDialog
{
    private Text messageField;
    private Button stageButton;
    private String message;
    private boolean stageTracked;

    CommitDialog(Shell parentShell)
    {
        super(parentShell);
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite container = (Composite) super.createDialogArea(parent);
        setTitle(Messages.get("safeCommitTitle")); //$NON-NLS-1$
        Composite fields = new Composite(container, SWT.NONE);
        fields.setLayout(new GridLayout(1, false));
        fields.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        new Label(fields, SWT.NONE).setText(Messages.get("commitMessage")); //$NON-NLS-1$
        messageField = new Text(fields, SWT.BORDER);
        messageField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        stageButton = new Button(fields, SWT.CHECK);
        stageButton.setText(Messages.get("stageTracked")); //$NON-NLS-1$
        return container;
    }

    @Override
    protected void okPressed()
    {
        message = messageField.getText().trim();
        if (message.isEmpty())
        {
            setErrorMessage(Messages.get("emptyCommitMessage")); //$NON-NLS-1$
            return;
        }
        stageTracked = stageButton.getSelection();
        super.okPressed();
    }

    String message() { return message; }
    boolean stageTracked() { return stageTracked; }
}
