package dev.edt.gitflow.ui.handlers;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.FilteredTree;

public final class MergeBranchPickerHook
{
    private static final String DIALOG = "org.eclipse.egit.ui.internal.dialogs.MergeTargetSelectionDialog"; //$NON-NLS-1$
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$
    private static final String ATTACHED = PLUGIN_ID + ".mergePickerAttached"; //$NON-NLS-1$

    private MergeBranchPickerHook()
    {
    }

    public static void install(Display display)
    {
        display.addFilter(SWT.Show, event ->
        {
            if (event.widget instanceof Shell shell && !Boolean.TRUE.equals(shell.getData(ATTACHED)))
            {
                Object dialog = findDialog(shell.getData());
                if (dialog != null)
                    attach(shell, dialog);
            }
        });
    }

    private static Object findDialog(Object value)
    {
        if (value == null)
            return null;
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass())
            if (DIALOG.equals(type.getName()))
                return value;
        return null;
    }

    private static void attach(Shell shell, Object dialog)
    {
        Control nativeTree = null;
        BranchPicker picker = null;
        try
        {
            Class<?> base = dialog.getClass();
            while (base != null)
            {
                try
                {
                    base.getDeclaredField("repo"); //$NON-NLS-1$
                    base.getDeclaredField("branchTree"); //$NON-NLS-1$
                    base.getDeclaredMethod("markRef", String.class); //$NON-NLS-1$
                    break;
                }
                catch (NoSuchFieldException | NoSuchMethodException e)
                {
                    base = base.getSuperclass();
                }
            }
            if (base == null)
                throw new NoSuchFieldException("EGit merge dialog fields were not found"); //$NON-NLS-1$
            Field repoField = base.getDeclaredField("repo"); //$NON-NLS-1$
            Field treeField = base.getDeclaredField("branchTree"); //$NON-NLS-1$
            Method markRef = base.getDeclaredMethod("markRef", String.class); //$NON-NLS-1$
            Field tagsField = base.getDeclaredField("tags"); //$NON-NLS-1$
            tagsField.setAccessible(true);
            Object tagsNode = tagsField.get(dialog);
            Class<?> tagNodeType = dialog.getClass().getClassLoader()
                .loadClass("org.eclipse.egit.ui.internal.repository.tree.TagNode"); //$NON-NLS-1$
            var tagNodeConstructor = tagNodeType.getConstructor(tagsField.getType(), Repository.class, Ref.class);
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
            Control original = nativeTree;
            Composite parent = nativeTree.getParent();
            picker = new BranchPicker(parent, repository, true, true, choice ->
            {
                try
                {
                    viewer.setSelection(StructuredSelection.EMPTY);
                    if (choice != null)
                    {
                        if (choice.kind() == BranchPicker.Kind.TAG)
                        {
                            Ref ref = repository.exactRef(choice.ref());
                            if (ref != null)
                                viewer.setSelection(new StructuredSelection(
                                    tagNodeConstructor.newInstance(tagsNode, repository, ref)), true);
                        }
                        else
                            markRef.invoke(dialog, choice.ref());
                    }
                }
                catch (IOException | ReflectiveOperationException e)
                {
                    visible(original, true);
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
            pickerData.widthHint = 760;
            pickerData.heightHint = 270;
            panel.moveAbove(nativeTree);
            visible(nativeTree, false);
            parent.layout(true, true);
            picker.focusSearch();
            MergeDialogSettings.attach(parent);
            shell.setData(ATTACHED, Boolean.TRUE);
        }
        catch (IOException | ReflectiveOperationException | RuntimeException e)
        {
            if (nativeTree != null && !nativeTree.isDisposed())
                visible(nativeTree, true);
            if (picker != null && !picker.control().isDisposed())
                picker.control().dispose();
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
            PLUGIN_ID, "Штатный выбор ветки EGit сохранен: адаптер слияния не сработал.", error)); //$NON-NLS-1$
    }
}
