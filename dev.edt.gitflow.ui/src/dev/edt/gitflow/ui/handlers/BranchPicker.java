package dev.edt.gitflow.ui.handlers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

final class BranchPicker
{
    record Choice(String name, String ref, boolean remote, boolean localExists)
    {
        String localName()
        {
            return remote ? name.substring(name.indexOf('/') + 1) : name;
        }
    }

    private final Tree tree;
    private final Button favoriteButton;
    private final Text search;
    private final List<Choice> branches = new ArrayList<>();
    private final Set<String> favorites = new LinkedHashSet<>();
    private final Preferences preferences;
    private final Consumer<Choice> onSelect;
    private final Runnable onActivate;
    private final String currentBranch;
    private Choice selected;

    BranchPicker(Composite parent, Repository repository, boolean includeRemote,
        Consumer<Choice> onSelect, Runnable onActivate) throws IOException
    {
        this.onSelect = onSelect;
        this.onActivate = onActivate;
        currentBranch = repository.getBranch();
        String id = UUID.nameUUIDFromBytes(repository.getDirectory().getAbsolutePath()
            .getBytes(StandardCharsets.UTF_8)).toString();
        preferences = InstanceScope.INSTANCE.getNode("dev.edt.gitflow.ui").node("favorites").node(id); //$NON-NLS-1$ //$NON-NLS-2$
        String saved = preferences.get("refs", ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (!saved.isEmpty())
            favorites.addAll(List.of(saved.split("\n"))); //$NON-NLS-1$
        Set<String> localNames = new LinkedHashSet<>();
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS))
        {
            String name = Repository.shortenRefName(ref.getName());
            localNames.add(name);
            branches.add(new Choice(name, ref.getName(), false, true));
        }
        if (includeRemote)
        {
            for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES))
            {
                if (!ref.isSymbolic() && !ref.getName().endsWith("/HEAD")) //$NON-NLS-1$
                {
                    String name = Repository.shortenRefName(ref.getName());
                    branches.add(new Choice(name, ref.getName(), true,
                        localNames.contains(name.substring(name.indexOf('/') + 1))));
                }
            }
        }
        branches.sort(Comparator.comparing(Choice::name));

        Composite panel = new Composite(parent, SWT.NONE);
        panel.setLayout(new GridLayout(2, false));
        GridData panelData = new GridData(SWT.FILL, SWT.FILL, true, true);
        panelData.horizontalSpan = 2;
        panel.setLayoutData(panelData);
        search = new Text(panel, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL);
        search.setMessage(Messages.get("searchBranches")); //$NON-NLS-1$
        search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        favoriteButton = new Button(panel, SWT.PUSH);
        favoriteButton.setText(Messages.get("addFavorite")); //$NON-NLS-1$
        favoriteButton.setEnabled(false);
        tree = new Tree(panel, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
        GridData treeData = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        treeData.heightHint = 220;
        treeData.widthHint = 400;
        tree.setLayoutData(treeData);
        search.addModifyListener(event -> rebuild());
        search.addListener(SWT.KeyDown, event ->
        {
            if (event.keyCode == SWT.ARROW_DOWN)
            {
                TreeItem first = firstLeaf();
                if (first != null)
                {
                    tree.setSelection(first);
                    choose((Choice) first.getData());
                    tree.setFocus();
                }
            }
        });
        search.addListener(SWT.DefaultSelection, event ->
        {
            if (!search.getText().isBlank())
            {
                TreeItem first = firstLeaf();
                if (first != null)
                {
                    choose((Choice) first.getData());
                    onActivate.run();
                }
            }
        });
        tree.addListener(SWT.Selection, event ->
        {
            selected = (Choice) ((TreeItem) event.item).getData();
            favoriteButton.setEnabled(selected != null);
            updateFavoriteButton();
            if (selected != null)
                onSelect.accept(selected);
        });
        tree.addListener(SWT.DefaultSelection, event ->
        {
            if (event.item instanceof TreeItem item && item.getData() instanceof Choice choice)
            {
                choose(choice);
                onActivate.run();
            }
            else if (event.item instanceof TreeItem item)
                item.setExpanded(!item.getExpanded());
        });
        favoriteButton.addListener(SWT.Selection, event -> toggleFavorite());
        rebuild();
    }

    void focusSearch()
    {
        if (!search.isDisposed())
            search.setFocus();
    }

    private void choose(Choice choice)
    {
        selected = choice;
        favoriteButton.setEnabled(true);
        updateFavoriteButton();
        onSelect.accept(choice);
    }

    private void toggleFavorite()
    {
        if (selected == null)
            return;
        boolean wasFavorite = favorites.contains(selected.ref());
        if (!favorites.add(selected.ref()))
            favorites.remove(selected.ref());
        preferences.put("refs", String.join("\n", favorites)); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            preferences.flush();
        }
        catch (BackingStoreException e)
        {
            if (wasFavorite)
                favorites.add(selected.ref());
            else
                favorites.remove(selected.ref());
            preferences.put("refs", String.join("\n", favorites)); //$NON-NLS-1$ //$NON-NLS-2$
            MessageDialog.openError(tree.getShell(), Messages.get("title"), //$NON-NLS-1$
                Messages.get("favoriteSaveFailed") + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        String ref = selected.ref();
        rebuild();
        select(ref);
    }

    private void updateFavoriteButton()
    {
        favoriteButton.setText(Messages.get(selected != null && favorites.contains(selected.ref())
            ? "removeFavorite" : "addFavorite")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void rebuild()
    {
        tree.removeAll();
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        TreeItem starred = root(Messages.get("favorites")); //$NON-NLS-1$
        TreeItem local = root(Messages.get("localBranches")); //$NON-NLS-1$
        TreeItem remote = root(Messages.get("remoteBranches")); //$NON-NLS-1$
        for (Choice branch : branches)
        {
            if (favorites.contains(branch.ref()))
            {
                TreeItem item = new TreeItem(starred, SWT.NONE);
                item.setText("★ " + displayName(branch)); //$NON-NLS-1$
                item.setData(branch);
            }
            if (branch.name().toLowerCase(Locale.ROOT).contains(query))
                addGrouped(branch.remote() ? remote : local, branch);
        }
        starred.setExpanded(true);
        local.setExpanded(true);
        remote.setExpanded(true);
        if (starred.getItemCount() == 0)
        {
            TreeItem empty = new TreeItem(starred, SWT.NONE);
            empty.setText(Messages.get("favoritesEmpty")); //$NON-NLS-1$
        }
        if (local.getItemCount() == 0)
            local.dispose();
        if (remote.getItemCount() == 0)
            remote.dispose();
        selected = null;
        favoriteButton.setEnabled(false);
        updateFavoriteButton();
        onSelect.accept(null);
    }

    private TreeItem root(String label)
    {
        TreeItem item = new TreeItem(tree, SWT.NONE);
        item.setText(label);
        item.setImage(PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_OBJ_FOLDER));
        return item;
    }

    private String displayName(Choice branch)
    {
        return branch.name() + (!branch.remote() && branch.name().equals(currentBranch)
            ? " " + Messages.get("currentBranchSuffix") : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private void addGrouped(TreeItem root, Choice branch)
    {
        String[] parts = branch.name().split("/"); //$NON-NLS-1$
        TreeItem parent = root;
        for (int i = 0; i < parts.length; i++)
        {
            TreeItem item = null;
            for (TreeItem child : parent.getItems())
            {
                if (child.getText().equals(parts[i]))
                {
                    item = child;
                    break;
                }
            }
            if (item == null)
            {
                item = new TreeItem(parent, SWT.NONE);
                item.setText(parts[i]);
                if (i < parts.length - 1)
                    item.setImage(PlatformUI.getWorkbench().getSharedImages()
                        .getImage(ISharedImages.IMG_OBJ_FOLDER));
            }
            if (i == parts.length - 1)
            {
                item.setData(branch);
                if (!branch.remote() && branch.name().equals(currentBranch))
                    item.setText(parts[i] + " " + Messages.get("currentBranchSuffix")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
                item.setExpanded(true);
            parent = item;
        }
    }

    private TreeItem firstLeaf()
    {
        for (TreeItem root : tree.getItems())
        {
            if (!search.getText().isBlank()
                && root.getText().equals(Messages.get("favorites"))) //$NON-NLS-1$
                continue;
            TreeItem found = firstLeaf(root);
            if (found != null)
                return found;
        }
        return null;
    }

    private static TreeItem firstLeaf(TreeItem item)
    {
        if (item.getData() instanceof Choice)
            return item;
        for (TreeItem child : item.getItems())
        {
            TreeItem found = firstLeaf(child);
            if (found != null)
                return found;
        }
        return null;
    }

    private void select(String ref)
    {
        for (TreeItem root : tree.getItems())
        {
            TreeItem found = find(root, ref);
            if (found != null)
            {
                tree.setSelection(found);
                choose((Choice) found.getData());
                return;
            }
        }
    }

    private static TreeItem find(TreeItem item, String ref)
    {
        if (item.getData() instanceof Choice choice && choice.ref().equals(ref))
            return item;
        for (TreeItem child : item.getItems())
        {
            TreeItem found = find(child, ref);
            if (found != null)
                return found;
        }
        return null;
    }
}
