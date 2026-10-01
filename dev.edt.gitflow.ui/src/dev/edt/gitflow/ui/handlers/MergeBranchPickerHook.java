package dev.edt.gitflow.ui.handlers;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.FilteredTree;

public final class MergeBranchPickerHook
{
    private static final String DIALOG = "org.eclipse.egit.ui.internal.dialogs.MergeTargetSelectionDialog"; //$NON-NLS-1$
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$

    private MergeBranchPickerHook()
    {
    }

    public static void install(Display display)
    {
        display.addFilter(SWT.Show, event ->
        {
            if (event.widget instanceof Shell shell && shell.getData() != null
                && DIALOG.equals(shell.getData().getClass().getName()))
                attach(shell);
        });
    }

    private static void attach(Shell shell)
    {
        Control nativeTree = null;
        BranchPicker picker = null;
        Link link = null;
        try
        {
            Object dialog = shell.getData();
            Class<?> base = dialog.getClass().getSuperclass();
            Field repoField = base.getDeclaredField("repo"); //$NON-NLS-1$
            Field treeField = base.getDeclaredField("branchTree"); //$NON-NLS-1$
            Method markRef = base.getDeclaredMethod("markRef", String.class); //$NON-NLS-1$
            repoField.setAccessible(true);
            treeField.setAccessible(true);
            markRef.setAccessible(true);
            Repository repository = (Repository) repoField.get(dialog);
            TreeViewer viewer = (TreeViewer) treeField.get(dialog);
            Control control = viewer.getControl();
            while (control != null && !(control instanceof FilteredTree))
                control = control.getParent();
            if (control == null || !(control.getLayoutData() instanceof GridData))
                return;
            nativeTree = control;
            Composite parent = nativeTree.getParent();
            Runnable[] restoreNative = { () -> { } };
            picker = new BranchPicker(parent, repository, true, choice ->
            {
                try
                {
                    viewer.setSelection(StructuredSelection.EMPTY);
                    if (choice != null)
                        markRef.invoke(dialog, choice.ref());
                }
                catch (ReflectiveOperationException e)
                {
                    restoreNative[0].run();
                    log(e);
                }
            }, () ->
            {
                var button = shell.getDefaultButton();
                if (button != null && button.isEnabled())
                {
                    button.notifyListeners(SWT.Selection, new Event());
                }
            });
            Composite panel = picker.control();
            GridData pickerData = (GridData) panel.getLayoutData();
            pickerData.horizontalSpan = 1;
            pickerData.widthHint = 500;
            pickerData.heightHint = 270;
            panel.moveAbove(nativeTree);

            link = new Link(parent, SWT.NONE);
            link.setText("<a>" + Messages.get("nativeRefPicker") + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            link.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
            link.moveBelow(panel);
            Control original = nativeTree;
            Link toggle = link;
            BranchPicker branchPicker = picker;
            restoreNative[0] = () ->
            {
                visible(original, true);
                visible(panel, false);
                toggle.setText("<a>" + Messages.get("branchPicker") + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                parent.layout(true, true);
            };
            link.addListener(SWT.Selection, event ->
            {
                boolean showNative = !original.isVisible();
                visible(original, showNative);
                visible(panel, !showNative);
                toggle.setText("<a>" + Messages.get(showNative
                    ? "branchPicker" : "nativeRefPicker") + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                parent.layout(true, true);
                if (showNative)
                    viewer.getControl().setFocus();
                else
                    branchPicker.focusSearch();
            });
            visible(nativeTree, false);
            parent.layout(true, true);
            picker.focusSearch();
        }
        catch (IOException | ReflectiveOperationException | RuntimeException e)
        {
            if (nativeTree != null && !nativeTree.isDisposed())
                visible(nativeTree, true);
            if (picker != null && !picker.control().isDisposed())
                picker.control().dispose();
            if (link != null && !link.isDisposed())
                link.dispose();
            if (nativeTree != null && !nativeTree.isDisposed())
                nativeTree.getParent().layout(true, true);
            log(e);
        }
    }

    private static void visible(Control control, boolean show)
    {
        control.setVisible(show);
        ((GridData) control.getLayoutData()).exclude = !show;
    }

    private static void log(Exception error)
    {
        Platform.getLog(Platform.getBundle(PLUGIN_ID)).log(new Status(IStatus.WARNING,
            PLUGIN_ID, "Штатный выбор ветки EGit сохранён: адаптер слияния не сработал.", error)); //$NON-NLS-1$
    }
}
