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

final class BranchDialog extends TitleAreaDialog
{
    enum Mode { CHECKOUT, COMMIT, MOVE }

    private final Mode mode;
    private Text branchField;
    private Text messageField;
    private Button createButton;
    private Button returnButton;
    private Button pushButton;
    private Button stageButton;
    private String branch;
    private String commitMessage;
    private boolean create;
    private boolean returnToOriginal;
    private boolean push;
    private boolean stageTracked;

    BranchDialog(Shell parentShell, Mode mode)
    {
        super(parentShell);
        this.mode = mode;
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite container = (Composite) super.createDialogArea(parent);
        setTitle(Messages.get(mode == Mode.CHECKOUT ? "checkoutTitle" //$NON-NLS-1$
            : mode == Mode.COMMIT ? "commitBranchTitle" : "moveTitle")); //$NON-NLS-1$ //$NON-NLS-2$
        Composite fields = new Composite(container, SWT.NONE);
        fields.setLayout(new GridLayout(2, false));
        fields.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        new Label(fields, SWT.NONE).setText(Messages.get("branchName")); //$NON-NLS-1$
        branchField = new Text(fields, SWT.BORDER);
        branchField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (mode != Mode.COMMIT)
        {
            createButton = checkbox(fields, Messages.get("createBranch"), false); //$NON-NLS-1$
        }
        if (mode == Mode.COMMIT)
        {
            new Label(fields, SWT.NONE).setText(Messages.get("commitMessage")); //$NON-NLS-1$
            messageField = new Text(fields, SWT.BORDER);
            messageField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            stageButton = checkbox(fields, Messages.get("stageTracked"), false); //$NON-NLS-1$
            pushButton = checkbox(fields, Messages.get("pushNewBranch"), false); //$NON-NLS-1$
        }
        if (mode != Mode.CHECKOUT)
            returnButton = checkbox(fields, Messages.get("returnToOriginal"), true); //$NON-NLS-1$
        return container;
    }

    private static Button checkbox(Composite parent, String label, boolean selected)
    {
        Button button = new Button(parent, SWT.CHECK);
        button.setText(label);
        button.setSelection(selected);
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.horizontalSpan = 2;
        button.setLayoutData(data);
        return button;
    }

    @Override
    protected void okPressed()
    {
        branch = branchField.getText().trim();
        if (!dev.edt.gitflow.core.BranchOperations.isValidBranchName(branch))
        {
            setErrorMessage(Messages.get("invalidBranch")); //$NON-NLS-1$
            return;
        }
        if (messageField != null)
        {
            commitMessage = messageField.getText().trim();
            if (commitMessage.isEmpty())
            {
                setErrorMessage(Messages.get("emptyCommitMessage")); //$NON-NLS-1$
                return;
            }
        }
        create = mode == Mode.COMMIT || createButton != null && createButton.getSelection();
        returnToOriginal = returnButton != null && returnButton.getSelection();
        push = pushButton != null && pushButton.getSelection();
        stageTracked = stageButton != null && stageButton.getSelection();
        super.okPressed();
    }

    String branch() { return branch; }
    String commitMessage() { return commitMessage; }
    boolean createBranch() { return create; }
    boolean returnToOriginal() { return returnToOriginal; }
    boolean push() { return push; }
    boolean stageTracked() { return stageTracked; }
}
