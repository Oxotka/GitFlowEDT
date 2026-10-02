package dev.edt.gitflow.ui.handlers;

import java.io.IOException;
import java.net.URL;
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
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.DecorationOverlayIcon;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

final class BranchPicker
{
    enum Kind { BRANCH, TAG }

    private record Folder(String path, boolean remote)
    {
    }

    record Choice(String name, String ref, boolean remote, boolean localExists, int lastActivity,
        String commitId, String commitSubject, Kind kind)
    {
        String localName()
        {
            return remote ? name.substring(name.indexOf('/') + 1) : name;
        }
    }

    private final Composite panel;
    private final Tree tree;
    private final Button favoriteButton;
    private final Text search;
    private final Image branchImage;
    private final Image currentBranchImage;
    private final Image tagImage;
    private final List<Choice> branches = new ArrayList<>();
    private final Set<String> favorites = new LinkedHashSet<>();
    private final Set<String> pinnedFolders = new LinkedHashSet<>();
    private final Preferences preferences;
    private final Consumer<Choice> onSelect;
    private final Runnable onActivate;
    private final String currentBranch;
    private final boolean includeTags;
    private Choice selected;

    BranchPicker(Composite parent, Repository repository, boolean includeRemote,
        Consumer<Choice> onSelect, Runnable onActivate) throws IOException
    {
        this(parent, repository, includeRemote, false, onSelect, onActivate);
    }

    BranchPicker(Composite parent, Repository repository, boolean includeRemote, boolean includeTags,
        Consumer<Choice> onSelect, Runnable onActivate) throws IOException
    {
        this.onSelect = onSelect;
        this.onActivate = onActivate;
        this.includeTags = includeTags;
        currentBranch = repository.getBranch();
        String id = UUID.nameUUIDFromBytes(repository.getDirectory().getAbsolutePath()
            .getBytes(StandardCharsets.UTF_8)).toString();
        preferences = InstanceScope.INSTANCE.getNode("dev.edt.gitflow.ui").node("favorites").node(id); //$NON-NLS-1$ //$NON-NLS-2$
        String saved = preferences.get("refs", ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (!saved.isEmpty())
            favorites.addAll(List.of(saved.split("\n"))); //$NON-NLS-1$
        String savedFolders = preferences.get("folders", ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (!savedFolders.isEmpty())
            pinnedFolders.addAll(List.of(savedFolders.split("\n"))); //$NON-NLS-1$
        Set<String> localNames = new LinkedHashSet<>();
        try (RevWalk walk = new RevWalk(repository))
        {
            for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS))
            {
                String name = Repository.shortenRefName(ref.getName());
                localNames.add(name);
                branches.add(choice(walk, name, ref, false, true, Kind.BRANCH));
            }
            if (includeRemote)
                for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES))
                {
                    if (!ref.isSymbolic() && !ref.getName().endsWith("/HEAD")) //$NON-NLS-1$
                    {
                        String name = Repository.shortenRefName(ref.getName());
                        branches.add(choice(walk, name, ref, true,
                            localNames.contains(name.substring(name.indexOf('/') + 1)), Kind.BRANCH));
                    }
                }
            if (includeTags)
            {
                for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_TAGS))
                    branches.add(choice(walk, Repository.shortenRefName(ref.getName()),
                        repository.getRefDatabase().peel(ref), false, false, Kind.TAG));
            }
        }
        branches.sort(byActivity());

        panel = new Composite(parent, SWT.NONE);
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
        tree = new Tree(panel, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION);
        GridData treeData = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        treeData.heightHint = 220;
        treeData.widthHint = 680;
        tree.setLayoutData(treeData);
        tree.setHeaderVisible(false);
        tree.setLinesVisible(false);
        TreeColumn branchColumn = new TreeColumn(tree, SWT.LEFT);
        branchColumn.setWidth(240);
        TreeColumn commitColumn = new TreeColumn(tree, SWT.LEFT);
        commitColumn.setWidth(360);
        tree.addListener(SWT.Resize, event -> commitColumn.setWidth(
            Math.max(120, tree.getClientArea().width - branchColumn.getWidth() - 28)));
        ImageDescriptor branchDescriptor = egitImage("icons/obj16/branch_obj.png"); //$NON-NLS-1$
        branchImage = branchDescriptor.createImage();
        ImageDescriptor checkedOverlay = egitImage("icons/ovr/checkedout_ov.png"); //$NON-NLS-1$
        currentBranchImage = new DecorationOverlayIcon(branchDescriptor, checkedOverlay,
            IDecoration.BOTTOM_LEFT).createImage();
        tagImage = egitImage("icons/obj16/tags.png").createImage(); //$NON-NLS-1$
        tree.addListener(SWT.Dispose, event ->
        {
            branchImage.dispose();
            currentBranchImage.dispose();
            tagImage.dispose();
        });
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
        Menu menu = new Menu(tree);
        tree.setMenu(menu);
        tree.addListener(SWT.MenuDetect, event ->
        {
            TreeItem item = event.x < 0 || event.y < 0 ? null
                : tree.getItem(tree.toControl(event.x, event.y));
            if ((event.x < 0 || event.y < 0) && tree.getSelectionCount() == 1)
                item = tree.getSelection()[0];
            if (item != null && item.getData() instanceof Folder folder && folder.remote())
                tree.setData("contextFolder", folder); //$NON-NLS-1$
            else
                event.doit = false;
        });
        menu.addListener(SWT.Show, event ->
        {
            for (MenuItem item : menu.getItems())
                item.dispose();
            if (!(tree.getData("contextFolder") instanceof Folder folder)) //$NON-NLS-1$
                return;
            tree.setData("contextFolder", null); //$NON-NLS-1$
            MenuItem pin = new MenuItem(menu, SWT.PUSH);
            pin.setText(Messages.get(pinnedFolders.contains(folder.path())
                ? "unpinFolder" : "pinFolder")); //$NON-NLS-1$ //$NON-NLS-2$
            pin.addListener(SWT.Selection, click -> toggleFolder(folder.path()));
        });
        rebuild();
    }

    Composite control()
    {
        return panel;
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

    private void toggleFolder(String path)
    {
        boolean wasPinned = pinnedFolders.contains(path);
        if (!pinnedFolders.add(path))
            pinnedFolders.remove(path);
        preferences.put("folders", String.join("\n", pinnedFolders)); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            preferences.flush();
        }
        catch (BackingStoreException e)
        {
            if (wasPinned)
                pinnedFolders.add(path);
            else
                pinnedFolders.remove(path);
            preferences.put("folders", String.join("\n", pinnedFolders)); //$NON-NLS-1$ //$NON-NLS-2$
            MessageDialog.openError(tree.getShell(), Messages.get("title"), //$NON-NLS-1$
                Messages.get("folderSaveFailed") + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        rebuild();
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
        TreeItem tags = includeTags ? root(Messages.get("tags")) : null; //$NON-NLS-1$
        List<Choice> localBranches = new ArrayList<>();
        List<Choice> remoteBranches = new ArrayList<>();
        List<Choice> tagRefs = new ArrayList<>();
        for (Choice branch : branches)
        {
            if (favorites.contains(branch.ref()))
            {
                TreeItem item = new TreeItem(starred, SWT.NONE);
                item.setText(new String[] { "★ " + branch.name(), commitText(branch) }); //$NON-NLS-1$
                setBranchImage(item, branch);
                item.setForeground(1, tree.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
                item.setData(branch);
            }
            if (branch.name().toLowerCase(Locale.ROOT).contains(query))
            {
                if (branch.kind() == Kind.TAG)
                    tagRefs.add(branch);
                else
                    (branch.remote() ? remoteBranches : localBranches).add(branch);
            }
        }
        addGroupedBranches(local, localBranches);
        addRemoteBranches(remote, remoteBranches);
        if (includeTags)
            addGroupedBranches(tags, tagRefs);
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
        if (tags != null && tags.getItemCount() == 0)
            tags.dispose();
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

    private void addGrouped(TreeItem root, Choice branch)
    {
        addGrouped(root, branch, branch.name(), ""); //$NON-NLS-1$
    }

    private void addGrouped(TreeItem root, Choice branch, String name, String prefix)
    {
        String[] parts = name.split("/"); //$NON-NLS-1$
        TreeItem parent = root;
        String path = prefix;
        for (int i = 0; i < parts.length; i++)
        {
            path = path.isEmpty() ? parts[i] : path + "/" + parts[i]; //$NON-NLS-1$
            TreeItem item = i < parts.length - 1 ? findFolder(parent, path) : findBranch(parent, branch.ref());
            if (item == null)
            {
                item = new TreeItem(parent, SWT.NONE);
                if (i < parts.length - 1)
                {
                    item.setText(pinnedFolders.contains(path) ? "★ " + parts[i] : parts[i]); //$NON-NLS-1$
                    item.setImage(PlatformUI.getWorkbench().getSharedImages()
                        .getImage(ISharedImages.IMG_OBJ_FOLDER));
                    item.setData(new Folder(path, branch.remote()));
                }
                else
                {
                    item.setText(new String[] { parts[i], commitText(branch) });
                    setBranchImage(item, branch);
                    item.setForeground(1, tree.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
                }
            }
            if (i == parts.length - 1)
            {
                item.setData(branch);
            }
            else
            {
                item.setExpanded(true);
            }
            parent = item;
        }
    }

    private void addGroupedBranches(TreeItem root, List<Choice> choices)
    {
        List<String> folders = new ArrayList<>();
        for (Choice choice : choices)
        {
            String[] parts = choice.name().split("/"); //$NON-NLS-1$
            String path = ""; //$NON-NLS-1$
            for (int i = 0; i < parts.length - 1; i++)
            {
                path = path.isEmpty() ? parts[i] : path + "/" + parts[i]; //$NON-NLS-1$
                if (!folders.contains(path))
                    folders.add(path);
            }
        }
        folders.sort(folderOrder(false));
        for (String folder : folders)
            addFolderPath(root, folder, "", false); //$NON-NLS-1$
        choices.sort(byActivity());
        for (Choice choice : choices)
            addGrouped(root, choice);
    }

    private void addRemoteBranches(TreeItem root, List<Choice> choices)
    {
        List<String> remotes = choices.stream().map(choice -> choice.name().substring(0, choice.name().indexOf('/')))
            .distinct().sorted(Comparator.comparing((String name) -> !name.equals("origin")) //$NON-NLS-1$
                .thenComparing(Comparator.naturalOrder())).toList();
        for (String remoteName : remotes)
        {
            TreeItem remoteRoot = findFolder(root, remoteName);
            if (remoteRoot == null)
            {
                remoteRoot = new TreeItem(root, SWT.NONE);
                remoteRoot.setText(pinnedFolders.contains(remoteName) ? "★ " + remoteName : remoteName); //$NON-NLS-1$
                remoteRoot.setImage(PlatformUI.getWorkbench().getSharedImages()
                    .getImage(ISharedImages.IMG_OBJ_FOLDER));
                remoteRoot.setData(new Folder(remoteName, true));
            }
            List<Choice> remoteChoices = choices.stream()
                .filter(choice -> choice.name().startsWith(remoteName + "/")) //$NON-NLS-1$
                .sorted(byActivity()).toList();
            List<String> folders = new ArrayList<>();
            for (Choice choice : remoteChoices)
            {
                String[] parts = choice.name().substring(remoteName.length() + 1).split("/"); //$NON-NLS-1$
                String path = ""; //$NON-NLS-1$
                for (int i = 0; i < parts.length - 1; i++)
                {
                    path = path.isEmpty() ? parts[i] : path + "/" + parts[i]; //$NON-NLS-1$
                    String fullPath = remoteName + "/" + path; //$NON-NLS-1$
                    if (!folders.contains(fullPath))
                        folders.add(fullPath);
                }
            }
            folders.sort(folderOrder(true));
            for (String folder : folders)
                addFolderPath(remoteRoot, folder.substring(remoteName.length() + 1), remoteName, true);
            for (Choice choice : remoteChoices)
                addGrouped(remoteRoot, choice, choice.name().substring(remoteName.length() + 1), remoteName);
            remoteRoot.setExpanded(true);
        }
    }

    private Comparator<String> folderOrder(boolean remote)
    {
        return Comparator.comparingInt(BranchPicker::pathDepth)
            .thenComparing(path -> remote && pinnedFolders.contains(path) ? 0 : 1)
            .thenComparing(Comparator.naturalOrder());
    }

    private void addFolderPath(TreeItem root, String relativePath, String prefix, boolean remote)
    {
        String path = prefix;
        TreeItem parent = root;
        for (String part : relativePath.split("/")) //$NON-NLS-1$
        {
            path = path.isEmpty() ? part : path + "/" + part; //$NON-NLS-1$
            TreeItem folder = findFolder(parent, path);
            if (folder == null)
            {
                folder = new TreeItem(parent, SWT.NONE);
                folder.setText(pinnedFolders.contains(path) ? "★ " + part : part); //$NON-NLS-1$
                folder.setImage(PlatformUI.getWorkbench().getSharedImages()
                    .getImage(ISharedImages.IMG_OBJ_FOLDER));
                folder.setData(new Folder(path, remote));
            }
            folder.setExpanded(true);
            parent = folder;
        }
    }

    private static int pathDepth(String path)
    {
        return (int) path.chars().filter(character -> character == '/').count();
    }

    private static Comparator<Choice> byActivity()
    {
        return Comparator.comparingInt(Choice::lastActivity).reversed().thenComparing(Choice::name);
    }

    private static TreeItem findFolder(TreeItem parent, String path)
    {
        for (TreeItem child : parent.getItems())
            if (child.getData() instanceof Folder folder && folder.path().equals(path))
                return child;
        return null;
    }

    private static TreeItem findBranch(TreeItem parent, String ref)
    {
        for (TreeItem child : parent.getItems())
            if (child.getData() instanceof Choice choice && choice.ref().equals(ref))
                return child;
        return null;
    }

    private static Choice choice(RevWalk walk, String name, Ref ref, boolean remote, boolean localExists, Kind kind)
        throws IOException
    {
        if (ref.getObjectId() == null)
            return new Choice(name, ref.getName(), remote, localExists, 0, "", "", kind); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            var objectId = ref.getPeeledObjectId() == null ? ref.getObjectId() : ref.getPeeledObjectId();
            var commit = walk.parseCommit(objectId);
            return new Choice(name, ref.getName(), remote, localExists, commit.getCommitTime(),
                commit.abbreviate(7).name(), commit.getShortMessage(), kind);
        }
        catch (IOException e)
        {
            if (kind == Kind.BRANCH)
                throw e;
            return new Choice(name, ref.getName(), remote, localExists, 0,
                ref.getObjectId().abbreviate(7).name(), "", kind); //$NON-NLS-1$
        }
    }

    private static ImageDescriptor egitImage(String path)
    {
        var bundle = Platform.getBundle("org.eclipse.egit.ui"); //$NON-NLS-1$
        URL entry = bundle == null ? null : bundle.getEntry(path);
        if (entry != null)
            return ImageDescriptor.createFromURL(entry);
        return PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_OBJ_ELEMENT);
    }

    private void setBranchImage(TreeItem item, Choice branch)
    {
        Image image = branch.kind() == Kind.TAG ? tagImage
            : !branch.remote() && branch.name().equals(currentBranch) ? currentBranchImage : branchImage;
        item.setImage(0, image);
    }

    private static String commitText(Choice branch)
    {
        return branch.commitId().isEmpty() ? "" : branch.commitId() + " " + branch.commitSubject(); //$NON-NLS-1$
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
