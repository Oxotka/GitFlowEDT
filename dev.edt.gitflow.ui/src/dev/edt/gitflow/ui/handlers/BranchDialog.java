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
    enum Mode { CHECKOUT, COMPARE, COMMIT, MOVE }

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
    private boolean edtBranchWizardRequested;
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

        if (mode != Mode.CHECKOUT && mode != Mode.COMPARE)
            createBranchField(fields);
        feedback = new Label(fields, SWT.WRAP);
        GridData feedbackData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        feedbackData.exclude = true;
        feedback.setLayoutData(feedbackData);
        feedback.setVisible(false);
        if (mode != Mode.COMMIT)
        {
            if (mode == Mode.MOVE)
                createButton = checkbox(fields, Messages.get("createBranch"), false); //$NON-NLS-1$
            String listError = null;
            try
            {
                boolean selectReference = mode == Mode.CHECKOUT || mode == Mode.COMPARE;
                BranchPicker picker = new BranchPicker(fields, repository, selectReference,
                    selectReference, choice ->
                {
                    if (choice == null)
                    {
                        setBranchTextIfAvailable(""); //$NON-NLS-1$
                        selectedChoice = null;
                        showFeedback(""); //$NON-NLS-1$
                    }
                    else
                    {
                        setBranchTextIfAvailable(choice.localName());
                        selectedChoice = choice;
                        if (createButton != null)
                            createButton.setSelection(false);
                        showFeedback(mode == Mode.COMPARE ? "" : choice.remote()
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
                        edtBranchWizardRequested = true;
                        cancelPressed();
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
        if (mode != Mode.CHECKOUT && mode != Mode.COMPARE)
            returnButton = checkbox(fields, Messages.get("returnToOriginal"), true); //$NON-NLS-1$
        return container;
    }

    @Override
    protected void configureShell(Shell shell)
    {
        super.configureShell(shell);
        shell.setText(Messages.get(mode == Mode.CHECKOUT ? "checkoutTitle" //$NON-NLS-1$
            : mode == Mode.COMPARE ? "compareBranchTitle" //$NON-NLS-1$
            : mode == Mode.COMMIT ? "commitBranchTitle" : "moveTitle")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    protected int getShellStyle()
    {
        return super.getShellStyle() | SWT.RESIZE;
    }

    private void setBranchText(String text)
    {
        settingBranch = true;
        branchField.setText(text);
        settingBranch = false;
    }

    private void setBranchTextIfAvailable(String text)
    {
        if (branchField != null)
            setBranchText(text);
    }

    private void createBranchField(Composite parent)
    {
        branchLabel = new Label(parent, SWT.NONE);
        branchLabel.setText(Messages.get("branchName")); //$NON-NLS-1$
        branchLabel.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        branchField = new Text(parent, SWT.BORDER);
        branchField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        branchField.addModifyListener(event ->
        {
            if (!settingBranch)
                selectedChoice = null;
        });
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
        boolean selectReference = mode == Mode.CHECKOUT || mode == Mode.COMPARE;
        branch = selectReference && selectedChoice != null
            ? selectedChoice.kind() == BranchPicker.Kind.TAG ? selectedChoice.ref() : selectedChoice.localName()
            : branchField == null ? "" : branchField.getText().trim(); //$NON-NLS-1$
        boolean tag = selectReference && selectedChoice != null
            && selectedChoice.kind() == BranchPicker.Kind.TAG;
        if (!tag && !dev.edt.gitflow.core.BranchOperations.isValidBranchName(branch))
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
        create = mode == Mode.COMMIT || mode == Mode.CHECKOUT
            && selectedChoice != null && selectedChoice.remote() && !selectedChoice.localExists()
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
    boolean edtBranchWizardRequested() { return edtBranchWizardRequested; }
    String selectedRef() { return selectedChoice == null ? null : selectedChoice.ref(); }
}
