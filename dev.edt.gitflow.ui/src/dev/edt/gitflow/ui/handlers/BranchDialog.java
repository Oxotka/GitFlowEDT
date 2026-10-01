package dev.edt.gitflow.ui.handlers;

import java.io.IOException;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

final class BranchDialog extends Dialog
{
    enum Mode { CHECKOUT, COMMIT, MOVE }

    private final Mode mode;
    private final Repository repository;
    private Text branchField;
    private Text messageField;
    private Button createButton;
    private Button returnButton;
    private Button pushButton;
    private Button stageButton;
    private Label branchLabel;
    private Label feedback;
    private boolean creatingBranch;
    private boolean settingBranch;
    private String branch;
    private String commitMessage;
    private boolean create;
    private boolean returnToOriginal;
    private boolean push;
    private boolean stageTracked;
    private BranchPicker.Choice selectedChoice;
    private String startPoint;

    BranchDialog(Shell parentShell, Mode mode, Repository repository)
    {
        super(parentShell);
        this.mode = mode;
        this.repository = repository;
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite container = (Composite) super.createDialogArea(parent);
        Composite fields = new Composite(container, SWT.NONE);
        fields.setLayout(new GridLayout(2, false));
        fields.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        branchLabel = new Label(fields, SWT.NONE);
        branchLabel.setText(Messages.get("branchName")); //$NON-NLS-1$
        branchLabel.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        branchField = new Text(fields, SWT.BORDER);
        branchField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        branchField.addModifyListener(event ->
        {
            if (!settingBranch)
                selectedChoice = null;
        });
        feedback = new Label(fields, SWT.WRAP);
        GridData feedbackData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        feedbackData.exclude = true;
        feedback.setLayoutData(feedbackData);
        feedback.setVisible(false);
        if (mode != Mode.COMMIT)
        {
            if (mode == Mode.MOVE)
                createButton = checkbox(fields, Messages.get("createBranch"), false); //$NON-NLS-1$
            else
                showBranchField(false);
            String listError = null;
            try
            {
                BranchPicker picker = new BranchPicker(fields, repository, mode == Mode.CHECKOUT, choice ->
                {
                    if (choice == null)
                    {
                        if (!creatingBranch)
                            setBranchText(""); //$NON-NLS-1$
                        selectedChoice = null;
                        showFeedback(""); //$NON-NLS-1$
                    }
                    else
                    {
                        creatingBranch = false;
                        if (mode == Mode.CHECKOUT)
                            showBranchField(false);
                        setBranchText(choice.localName());
                        selectedChoice = choice;
                        if (createButton != null)
                            createButton.setSelection(false);
                        showFeedback(choice.remote()
                            ? Messages.get(choice.localExists() ? "remoteUsesLocal" : "remoteCreatesLocal") //$NON-NLS-1$ //$NON-NLS-2$
                                + " " + choice.localName() //$NON-NLS-1$
                            : ""); //$NON-NLS-1$
                    }
                }, this::okPressed);
                if (mode == Mode.CHECKOUT)
                {
                    Link createLink = new Link(fields, SWT.NONE);
                    createLink.setText("<a>" + Messages.get("createBranch") + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    createLink.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false, 2, 1));
                    createLink.addListener(SWT.Selection, event ->
                    {
                        creatingBranch = true;
                        selectedChoice = null;
                        setBranchText(""); //$NON-NLS-1$
                        showFeedback(""); //$NON-NLS-1$
                        showBranchField(true);
                        branchField.setFocus();
                    });
                }
                getShell().getDisplay().asyncExec(picker::focusSearch);
            }
            catch (IOException e)
            {
                listError = Messages.get("branchListFailed") + " " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (listError != null)
                showFeedback(listError);
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

    @Override
    protected void configureShell(Shell shell)
    {
        super.configureShell(shell);
        shell.setText(Messages.get(mode == Mode.CHECKOUT ? "checkoutTitle" //$NON-NLS-1$
            : mode == Mode.COMMIT ? "commitBranchTitle" : "moveTitle")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void setBranchText(String text)
    {
        settingBranch = true;
        branchField.setText(text);
        settingBranch = false;
    }

    private void showBranchField(boolean show)
    {
        branchLabel.setVisible(show);
        ((GridData) branchLabel.getLayoutData()).exclude = !show;
        branchField.setVisible(show);
        ((GridData) branchField.getLayoutData()).exclude = !show;
        branchField.getParent().layout(true, true);
    }

    private void showFeedback(String text)
    {
        feedback.setText(text);
        feedback.setVisible(!text.isEmpty());
        ((GridData) feedback.getLayoutData()).exclude = text.isEmpty();
        feedback.getParent().layout(true, true);
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
            showFeedback(Messages.get("invalidBranch")); //$NON-NLS-1$
            return;
        }
        if (messageField != null)
        {
            commitMessage = messageField.getText().trim();
            if (commitMessage.isEmpty())
            {
                showFeedback(Messages.get("emptyCommitMessage")); //$NON-NLS-1$
                return;
            }
        }
        create = mode == Mode.COMMIT || mode == Mode.CHECKOUT && (creatingBranch
            || selectedChoice != null && selectedChoice.remote() && !selectedChoice.localExists())
            || mode == Mode.MOVE && createButton.getSelection();
        startPoint = selectedChoice != null && selectedChoice.remote() && create
            ? selectedChoice.ref() : null;
        returnToOriginal = returnButton != null && returnButton.getSelection();
        push = pushButton != null && pushButton.getSelection();
        stageTracked = stageButton != null && stageButton.getSelection();
        super.okPressed();
    }

    String branch() { return branch; }
    String commitMessage() { return commitMessage; }
    boolean createBranch() { return create; }
    String startPoint() { return startPoint; }
    boolean returnToOriginal() { return returnToOriginal; }
    boolean push() { return push; }
    boolean stageTracked() { return stageTracked; }
}
