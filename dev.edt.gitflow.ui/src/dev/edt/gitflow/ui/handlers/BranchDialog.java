package dev.edt.gitflow.ui.handlers;

import java.io.IOException;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;

final class BranchDialog extends Dialog
{
    enum Mode { CHECKOUT, COMPARE }

    private final Mode mode;
    private final Repository repository;
    private Label feedback;
    private boolean edtBranchWizardRequested;
    private String branch;
    private boolean create;
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

        feedback = new Label(fields, SWT.WRAP);
        GridData feedbackData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        feedbackData.exclude = true;
        feedback.setLayoutData(feedbackData);
        feedback.setVisible(false);
        String listError = null;
        try
        {
            BranchPicker picker = new BranchPicker(fields, repository, true, true, choice ->
            {
                selectedChoice = choice;
                if (choice == null)
                    showFeedback(""); //$NON-NLS-1$
                else
                    showFeedback(mode == Mode.COMPARE ? "" : choice.remote() //$NON-NLS-1$
                        ? Messages.get(choice.localExists() ? "remoteUsesLocal" : "remoteCreatesLocal") //$NON-NLS-1$ //$NON-NLS-2$
                            + " " + choice.localName() //$NON-NLS-1$
                        : ""); //$NON-NLS-1$
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
        return container;
    }

    @Override
    protected void configureShell(Shell shell)
    {
        super.configureShell(shell);
        shell.setText(Messages.get(mode == Mode.CHECKOUT ? "checkoutTitle" : "compareBranchTitle")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    protected int getShellStyle()
    {
        return super.getShellStyle() | SWT.RESIZE;
    }

    private void showFeedback(String text)
    {
        feedback.setText(text);
        feedback.setVisible(!text.isEmpty());
        ((GridData) feedback.getLayoutData()).exclude = text.isEmpty();
        feedback.getParent().layout(true, true);
    }

    @Override
    protected void okPressed()
    {
        branch = selectedChoice != null
            ? selectedChoice.kind() == BranchPicker.Kind.TAG ? selectedChoice.ref() : selectedChoice.localName()
            : ""; //$NON-NLS-1$
        boolean tag = selectedChoice != null && selectedChoice.kind() == BranchPicker.Kind.TAG;
        if (!tag && !dev.edt.gitflow.core.BranchOperations.isValidBranchName(branch))
        {
            showFeedback(Messages.get("invalidBranch")); //$NON-NLS-1$
            return;
        }
        create = mode == Mode.CHECKOUT && selectedChoice != null
            && selectedChoice.remote() && !selectedChoice.localExists();
        startPoint = selectedChoice != null && selectedChoice.remote() && create
            ? selectedChoice.ref() : null;
        super.okPressed();
    }

    String branch() { return branch; }
    boolean createBranch() { return create; }
    String startPoint() { return startPoint; }
    boolean edtBranchWizardRequested() { return edtBranchWizardRequested; }
    String selectedRef() { return selectedChoice == null ? null : selectedChoice.ref(); }
}
