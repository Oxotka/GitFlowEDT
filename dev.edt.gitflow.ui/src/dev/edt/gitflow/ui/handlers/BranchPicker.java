package dev.edt.gitflow.ui.handlers;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
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
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
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
        String author, String commitSubject, Kind kind)
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
    private final Font smallFont;
    private final List<Choice> branches = new ArrayList<>();
    private final Set<String> favorites = new LinkedHashSet<>();
    private final Set<String> pinnedFolders = new LinkedHashSet<>();
    private final Preferences preferences;
    private final Consumer<Choice> onSelect;
    private final Runnable onActivate;
    private final String currentBranch;
    private final boolean includeTags;
    private Choice selected;
    private boolean localLoading = true;
    private boolean remoteLoading;
    private boolean tagsLoading;
    private String localLoadError;
    private String remoteLoadError;
    private String tagLoadError;

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
        remoteLoading = includeRemote;
        tagsLoading = includeTags;
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
        panel = new Composite(parent, SWT.NONE);
        panel.setLayout(new GridLayout(2, false));
        GridData panelData = new GridData(SWT.FILL, SWT.FILL, true, true);
        panelData.horizontalSpan = 2;
        panel.setLayoutData(panelData);
        search = new Text(panel, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL);
        search.setMessage(Messages.get("searchBranches")); //$NON-NLS-1$
        search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        favoriteButton = new Button(panel, SWT.PUSH);
        favoriteButton.setText(Messages.get("removeFavorite")); //$NON-NLS-1$
        int favoriteWidth = favoriteButton.computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
        favoriteButton.setText(Messages.get("addFavorite")); //$NON-NLS-1$
        GridData favoriteData = new GridData(SWT.FILL, SWT.CENTER, false, false);
        favoriteData.widthHint = Math.max(favoriteWidth,
            favoriteButton.computeSize(SWT.DEFAULT, SWT.DEFAULT).x);
        favoriteButton.setLayoutData(favoriteData);
        favoriteButton.setEnabled(false);
        tree = new Tree(panel, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION);
        GridData treeData = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        treeData.heightHint = 220;
        treeData.widthHint = 680;
        tree.setLayoutData(treeData);
        tree.setHeaderVisible(false);
        tree.setLinesVisible(false);
        FontData[] smallData = tree.getFont().getFontData();
        for (FontData data : smallData)
            data.setHeight(Math.max(8, data.getHeight() - 1));
        smallFont = new Font(tree.getDisplay(), smallData);
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
            smallFont.dispose();
        });
        tree.addListener(SWT.EraseItem, event ->
        {
            if (event.index == 0 && event.item.getData() instanceof Choice)
                event.detail &= ~SWT.FOREGROUND;
        });
        tree.addListener(SWT.PaintItem, event ->
        {
            if (event.index != 0 || !(event.item instanceof TreeItem item)
                || !(item.getData() instanceof Choice choice))
                return;
            String label = (String) item.getData("label"); //$NON-NLS-1$
            GC gc = event.gc;
            Font originalFont = gc.getFont();
            Color originalColor = gc.getForeground();
            Color primary = tree.getForeground();
            Color secondary = tree.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY);
            try
            {
                Image icon = item.getImage(0);
                if (icon != null)
                {
                    var bounds = item.getImageBounds(0);
                    gc.drawImage(icon, bounds.x, bounds.y);
                }
                int x = item.getTextBounds(0).x;
                x = paintPart(gc, label + "  ", originalFont, primary, x, event.y, event.height); //$NON-NLS-1$
                if (choice.lastActivity() > 0)
                    x = paintPart(gc, relativeTime(choice.lastActivity()) + "  ·  ", //$NON-NLS-1$
                        smallFont, secondary, x, event.y, event.height);
                if (!choice.author().isEmpty())
                    x = paintPart(gc, choice.author() + "  ·  ", smallFont, secondary, //$NON-NLS-1$
                        x, event.y, event.height);
                paintPart(gc, choice.commitSubject(), originalFont, primary, x, event.y, event.height);
            }
            finally
            {
                gc.setFont(originalFont);
                gc.setForeground(originalColor);
            }
        });
        search.addModifyListener(event -> rebuild());
        Display display = tree.getDisplay();
        Listener quickSearch = event ->
        {
            Control focus = display.getFocusControl();
            char character = event.character;
            if (focus != search && isInside(focus, panel) && !Character.isISOControl(character)
                && character != 0 && (event.stateMask & (SWT.CTRL | SWT.ALT | SWT.COMMAND)) == 0)
            {
                search.setText(search.getText() + character);
                search.setFocus();
                search.setSelection(search.getText().length());
                event.doit = false;
            }
        };
        display.addFilter(SWT.KeyDown, quickSearch);
        panel.addListener(SWT.Dispose, event -> display.removeFilter(SWT.KeyDown, quickSearch));
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
        load(repository, includeRemote);
    }

    private void load(Repository repository, boolean includeRemote)
    {
        Job job = new Job(Messages.get("searchBranches")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                Set<String> localNames = new LinkedHashSet<>();
                List<Choice> local = new ArrayList<>();
                try (RevWalk walk = new RevWalk(repository))
                {
                    for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_HEADS))
                    {
                        if (monitor.isCanceled())
                            return Status.CANCEL_STATUS;
                        String name = Repository.shortenRefName(ref.getName());
                        localNames.add(name);
                        local.add(choice(walk, name, ref, false, true, Kind.BRANCH));
                    }
                    local.sort(byActivity());
                }
                catch (IOException e)
                {
                    postError(e, true);
                    return Status.OK_STATUS;
                }
                post(local, () -> localLoading = false);
                if (includeRemote)
                {
                    List<Choice> remote = new ArrayList<>();
                    try (RevWalk walk = new RevWalk(repository))
                    {
                        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES))
                        {
                            if (monitor.isCanceled())
                                return Status.CANCEL_STATUS;
                            if (!ref.isSymbolic() && !ref.getName().endsWith("/HEAD")) //$NON-NLS-1$
                            {
                                String name = Repository.shortenRefName(ref.getName());
                                remote.add(choice(walk, name, ref, true,
                                    localNames.contains(name.substring(name.indexOf('/') + 1)), Kind.BRANCH));
                            }
                        }
                        remote.sort(byActivity());
                    }
                    catch (IOException e)
                    {
                        postError(e, false);
                        return Status.OK_STATUS;
                    }
                    post(remote, () -> remoteLoading = false);
                }
                if (!includeTags)
                    return Status.OK_STATUS;
                List<Choice> tags = new ArrayList<>();
                String error = null;
                try (RevWalk walk = new RevWalk(repository))
                {
                    for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_TAGS))
                    {
                        if (monitor.isCanceled())
                            return Status.CANCEL_STATUS;
                        tags.add(choice(walk, Repository.shortenRefName(ref.getName()),
                            repository.getRefDatabase().peel(ref), false, false, Kind.TAG));
                    }
                    tags.sort(byActivity());
                }
                catch (IOException e)
                {
                    error = Messages.get("tagListFailed") + " " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
                }
                String finalError = error;
                post(tags, () ->
                {
                    tagLoadError = finalError;
                    tagsLoading = false;
                });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        panel.addListener(SWT.Dispose, event -> job.cancel());
        job.schedule();
    }

    private void post(List<Choice> choices, Runnable update)
    {
        Display.getDefault().asyncExec(() ->
        {
            if (panel.isDisposed())
                return;
            branches.addAll(choices);
            update.run();
            rebuild();
        });
    }

    private void postError(IOException error, boolean local)
    {
        post(List.of(), () ->
        {
            if (local)
            {
                localLoadError = Messages.get("branchListFailed") + " " + error.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
                localLoading = false;
                remoteLoading = false;
            }
            else
            {
                remoteLoadError = Messages.get("branchListFailed") + " " + error.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
                remoteLoading = false;
            }
            tagsLoading = false;
        });
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

    private static boolean isInside(Control control, Composite parent)
    {
        while (control != null)
        {
            if (control == parent)
                return true;
            control = control.getParent();
        }
        return false;
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
        rebuild();
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
        tree.setRedraw(false);
        try
        {
            String selectedRef = selected == null ? null : selected.ref();
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
                    item.setText(rowText("★ " + branch.name(), branch)); //$NON-NLS-1$
                    setBranchImage(item, branch);
                    item.setData(branch);
                    item.setData("label", "★ " + branch.name()); //$NON-NLS-1$ //$NON-NLS-2$
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
            if (localLoadError != null)
            {
                TreeItem status = new TreeItem(local, SWT.NONE);
                status.setText(localLoadError);
            }
            if (remoteLoadError != null)
            {
                TreeItem status = new TreeItem(remote, SWT.NONE);
                status.setText(remoteLoadError);
            }
            if (tags != null && (tagsLoading || tagLoadError != null))
            {
                TreeItem status = new TreeItem(tags, SWT.NONE);
                status.setText(tagLoadError == null ? Messages.get("loadingTags") : tagLoadError); //$NON-NLS-1$
            }
            starred.setExpanded(true);
            local.setExpanded(true);
            remote.setExpanded(true);
            if (starred.getItemCount() == 0)
            {
                TreeItem empty = new TreeItem(starred, SWT.NONE);
                empty.setText(Messages.get("favoritesEmpty")); //$NON-NLS-1$
            }
            if (local.getItemCount() == 0 && !localLoading)
                local.dispose();
            if (remote.getItemCount() == 0 && !remoteLoading)
                remote.dispose();
            if (tags != null && tags.getItemCount() == 0)
                tags.dispose();
            if (selectedRef == null || !select(selectedRef))
            {
                selected = null;
                favoriteButton.setEnabled(false);
                updateFavoriteButton();
                onSelect.accept(null);
            }
        }
        finally
        {
            tree.setRedraw(true);
        }
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
            TreeItem item = i < parts.length - 1 ? findFolder(parent, path) : null;
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
                    String label = favorites.contains(branch.ref()) ? "★ " + parts[i] : parts[i]; //$NON-NLS-1$
                    item.setText(rowText(label, branch));
                    setBranchImage(item, branch);
                    item.setData("label", label); //$NON-NLS-1$
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
        Set<String> folderPaths = new LinkedHashSet<>();
        for (Choice choice : choices)
        {
            String[] parts = choice.name().split("/"); //$NON-NLS-1$
            String path = ""; //$NON-NLS-1$
            for (int i = 0; i < parts.length - 1; i++)
            {
                path = path.isEmpty() ? parts[i] : path + "/" + parts[i]; //$NON-NLS-1$
                folderPaths.add(path);
            }
        }
        List<String> folders = new ArrayList<>(folderPaths);
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
            Set<String> folderPaths = new LinkedHashSet<>();
            for (Choice choice : remoteChoices)
            {
                String[] parts = choice.name().substring(remoteName.length() + 1).split("/"); //$NON-NLS-1$
                String path = ""; //$NON-NLS-1$
                for (int i = 0; i < parts.length - 1; i++)
                {
                    path = path.isEmpty() ? parts[i] : path + "/" + parts[i]; //$NON-NLS-1$
                    String fullPath = remoteName + "/" + path; //$NON-NLS-1$
                    folderPaths.add(fullPath);
                }
            }
            List<String> folders = new ArrayList<>(folderPaths);
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
                commit.getAuthorIdent().getName(), commit.getShortMessage(), kind);
        }
        catch (IOException e)
        {
            if (kind == Kind.BRANCH)
                throw e;
            return new Choice(name, ref.getName(), remote, localExists, 0, "", "", kind); //$NON-NLS-1$ //$NON-NLS-2$
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

    private static String rowText(String name, Choice branch)
    {
        String age = branch.lastActivity() > 0
            ? "  " + relativeTime(branch.lastActivity()) : ""; //$NON-NLS-1$ //$NON-NLS-2$
        String author = branch.author().isEmpty() ? "" : "  ·  " + branch.author(); //$NON-NLS-1$ //$NON-NLS-2$
        String subject = branch.commitSubject().isEmpty() ? "" : "  ·  " + branch.commitSubject(); //$NON-NLS-1$ //$NON-NLS-2$
        return name + age + author + subject;
    }

    private static int paintPart(GC gc, String text, Font font, Color color,
        int x, int y, int height)
    {
        gc.setFont(font);
        gc.setForeground(color);
        gc.drawText(text, x, y + Math.max(0, (height - gc.getFontMetrics().getHeight()) / 2), true);
        return x + gc.textExtent(text).x;
    }

    private static String relativeTime(int commitTime)
    {
        long seconds = Math.max(0, System.currentTimeMillis() / 1000 - commitTime);
        if (seconds < 60)
            return Messages.get("timeNow"); //$NON-NLS-1$
        String unit;
        long count;
        if (seconds < 3600)
        {
            unit = "timeMinute"; //$NON-NLS-1$
            count = seconds / 60;
        }
        else if (seconds < 86400)
        {
            unit = "timeHour"; //$NON-NLS-1$
            count = seconds / 3600;
        }
        else if (seconds < 604800)
        {
            unit = "timeDay"; //$NON-NLS-1$
            count = seconds / 86400;
        }
        else if (seconds < 2592000)
        {
            unit = "timeWeek"; //$NON-NLS-1$
            count = seconds / 604800;
        }
        else if (seconds < 31536000)
        {
            unit = "timeMonth"; //$NON-NLS-1$
            count = seconds / 2592000;
        }
        else
        {
            unit = "timeYear"; //$NON-NLS-1$
            count = seconds / 31536000;
        }
        String[] forms = Messages.get(unit).split("\\|"); //$NON-NLS-1$
        int form = forms.length == 2 ? (count == 1 ? 0 : 1)
            : count % 100 >= 11 && count % 100 <= 14 ? 2
            : count % 10 == 1 ? 0 : count % 10 >= 2 && count % 10 <= 4 ? 1 : 2;
        return MessageFormat.format(Messages.get("timeAgo"), count, forms[form]); //$NON-NLS-1$
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

    private boolean select(String ref)
    {
        for (TreeItem root : tree.getItems())
        {
            TreeItem found = find(root, ref);
            if (found != null)
            {
                tree.setSelection(found);
                choose((Choice) found.getData());
                return true;
            }
        }
        return false;
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
