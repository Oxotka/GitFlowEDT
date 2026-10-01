package dev.edt.gitflow.ui.views;

import java.io.File;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import org.osgi.service.prefs.BackingStoreException;

import dev.edt.gitflow.core.CommitOperations;
import dev.edt.gitflow.core.PullOperations;
import dev.edt.gitflow.core.RepositoryOverview;
import dev.edt.gitflow.core.RepositorySupport;
import dev.edt.gitflow.core.WorkingChanges;
import dev.edt.gitflow.core.WorkingChanges.FileChange;
import dev.edt.gitflow.ui.handlers.Messages;
import dev.edt.gitflow.ui.handlers.OperationJob;
import dev.edt.gitflow.ui.handlers.Repositories;
import dev.edt.gitflow.ui.handlers.SmartCheckoutHandler;

public class GitFlowView extends ViewPart
{
    public static final String ID = "dev.edt.gitflow.ui.view.operations"; //$NON-NLS-1$
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$
    private static final StringBuilder HISTORY = new StringBuilder();
    private static final Map<File, Integer> RUNNING = new HashMap<>();
    private static GitFlowView instance;
    private static Repository preferredRepository;

    private final List<Repository> repositories = new ArrayList<>();
    private final RepositorySelection selectionProvider = new RepositorySelection();
    private final ISelectionListener selectionListener = this::selectionChanged;
    private final IResourceChangeListener resourceListener = event -> onUi(() ->
    {
        if (instance == this)
            scheduleRefresh();
    });
    private Composite container;
    private Combo repositoryCombo;
    private Button branchButton;
    private Label countsLabel;
    private Text messageField;
    private Button primaryButton;
    private Tree changesTree;
    private boolean stagedExpanded = true;
    private boolean unstagedExpanded = true;
    private String movedPath;
    private boolean movedToStaged;
    private Label feedbackLabel;
    private Job refreshJob;
    private int generation;
    private File displayedDirectory;
    private boolean hasRemote;
    private boolean sendAfterCommit;
    private RepositoryOverview overview = new RepositoryOverview(0, -1, -1);
    private WorkingChanges changes = new WorkingChanges(List.of(), List.of());

    @Override
    public void createPartControl(Composite parent)
    {
        instance = this;
        container = parent;
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 8;
        layout.marginHeight = 8;
        layout.verticalSpacing = 7;
        parent.setLayout(layout);
        sendAfterCommit = InstanceScope.INSTANCE.getNode(PLUGIN_ID)
            .getBoolean("sendAfterCommit", true); //$NON-NLS-1$

        Composite header = new Composite(parent, SWT.NONE);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout headerLayout = new GridLayout(3, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 0;
        header.setLayout(headerLayout);
        branchButton = new Button(header, SWT.PUSH);
        branchButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        branchButton.setToolTipText(Messages.get("switchBranchHint")); //$NON-NLS-1$
        branchButton.addListener(SWT.Selection, event ->
        {
            Repository repository = selectedRepository();
            if (repository != null)
                SmartCheckoutHandler.open(getSite().getShell(), repository);
        });
        countsLabel = new Label(header, SWT.NONE);
        countsLabel.setToolTipText(Messages.get("statusHint")); //$NON-NLS-1$
        Button more = new Button(header, SWT.PUSH);
        more.setText("⋯"); //$NON-NLS-1$
        more.setToolTipText(Messages.get("moreActions")); //$NON-NLS-1$
        more.addListener(SWT.Selection, event -> showMenu(more));

        repositoryCombo = new Combo(parent, SWT.DROP_DOWN | SWT.READ_ONLY);
        repositoryCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        repositoryCombo.addListener(SWT.Selection, event -> selectRepository(selectedRepository()));

        Label messageLabel = new Label(parent, SWT.NONE);
        messageLabel.setText(Messages.get("commitMessage")); //$NON-NLS-1$
        messageField = new Text(parent, SWT.BORDER | SWT.SINGLE);
        messageField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        messageField.addModifyListener(event -> updatePrimary());
        primaryButton = new Button(parent, SWT.PUSH);
        primaryButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        primaryButton.addListener(SWT.Selection, event -> runPrimary());

        changesTree = changeTree(parent);
        feedbackLabel = new Label(parent, SWT.WRAP);
        feedbackLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        feedbackLabel.setText(Messages.get("ready")); //$NON-NLS-1$

        fillChangesTree();
        loadRepositories();
        getSite().setSelectionProvider(selectionProvider);
        getSite().getPage().addSelectionListener(selectionListener);
        ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener,
            IResourceChangeEvent.POST_CHANGE);
    }

    private Tree changeTree(Composite parent)
    {
        Tree tree = new Tree(parent, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER | SWT.V_SCROLL);
        GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
        data.heightHint = 140;
        tree.setLayoutData(data);
        TreeColumn file = new TreeColumn(tree, SWT.LEFT);
        file.setWidth(260);
        TreeColumn action = new TreeColumn(tree, SWT.CENTER);
        action.setWidth(32);
        tree.addListener(SWT.Resize, event ->
            file.setWidth(Math.max(100, tree.getClientArea().width - action.getWidth() - 3)));
        tree.addListener(SWT.Expand, event -> rememberExpansion((TreeItem) event.item, true));
        tree.addListener(SWT.Collapse, event -> rememberExpansion((TreeItem) event.item, false));
        tree.addListener(SWT.MouseMove, event ->
        {
            TreeItem item = tree.getItem(new Point(event.x, event.y));
            if (item == null)
                tree.setToolTipText(null);
            else if (item.getBounds(1).contains(event.x, event.y))
            {
                boolean staged = item.getData() instanceof Boolean value ? value
                    : Boolean.TRUE.equals(item.getParentItem().getData());
                tree.setToolTipText(Messages.get(item.getData() instanceof Boolean
                    ? staged ? "unstageAll" : "stageAll" //$NON-NLS-1$ //$NON-NLS-2$
                    : staged ? "unstageFile" : "stageFile")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
                tree.setToolTipText(item.getData() instanceof FileChange
                    ? Messages.get("openDiffHint") : null); //$NON-NLS-1$
        });
        tree.addListener(SWT.MouseDown, event ->
        {
            TreeItem item = tree.getItem(new Point(event.x, event.y));
            if (item == null)
                return;
            if (item.getBounds(1).contains(event.x, event.y))
                activateChange(item, true);
            else if (item.getData() instanceof FileChange)
                activateChange(item, false);
        });
        tree.addListener(SWT.KeyDown, event ->
        {
            TreeItem[] selected = tree.getSelection();
            if (selected.length == 0)
                return;
            if (event.keyCode == SWT.CR)
                activateChange(selected[0], false);
            else if (event.keyCode == ' ')
                activateChange(selected[0], true);
        });
        return tree;
    }

    private void rememberExpansion(TreeItem item, boolean expanded)
    {
        if (item.getData() instanceof Boolean staged)
        {
            if (staged)
                stagedExpanded = expanded;
            else
                unstagedExpanded = expanded;
        }
    }

    private void activateChange(TreeItem item, boolean action)
    {
        if (item.getData() instanceof Boolean staged)
        {
            if (action && item.getItemCount() > 0)
                changeAll(staged);
        }
        else if (item.getData() instanceof FileChange change)
        {
            boolean staged = Boolean.TRUE.equals(item.getParentItem().getData());
            if (action)
                changeFile(change.path(), staged);
            else
                openDiff(change.path(), staged);
        }
    }

    private void showMenu(Button anchor)
    {
        Menu menu = new Menu(anchor);
        menuItem(menu, Messages.get("syncChanges"), () -> runSync()); //$NON-NLS-1$
        menuItem(menu, Messages.get("pullJob"), () -> runPull()); //$NON-NLS-1$
        menuItem(menu, Messages.get("smartPushJob"), () -> runSync()); //$NON-NLS-1$
        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem send = new MenuItem(menu, SWT.CHECK);
        send.setText(Messages.get("sendAfterCommit")); //$NON-NLS-1$
        send.setSelection(sendAfterCommit);
        send.addListener(SWT.Selection, event ->
        {
            sendAfterCommit = send.getSelection();
            var preferences = InstanceScope.INSTANCE.getNode(PLUGIN_ID);
            preferences.putBoolean("sendAfterCommit", sendAfterCommit); //$NON-NLS-1$
            try
            {
                preferences.flush();
            }
            catch (BackingStoreException e)
            {
                publish(Messages.get("settingSaveFailed") + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            updatePrimary();
        });
        menuItem(menu, Messages.get("showHistory"), () -> MessageDialog.openInformation(
            getSite().getShell(), Messages.get("title"), HISTORY.toString())); //$NON-NLS-1$ //$NON-NLS-2$
        menu.addListener(SWT.Hide, event -> anchor.getDisplay().asyncExec(menu::dispose));
        menu.setLocation(anchor.toDisplay(0, anchor.getSize().y));
        menu.setVisible(true);
    }

    private static void menuItem(Menu menu, String title, Runnable action)
    {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(title);
        item.addListener(SWT.Selection, event -> action.run());
    }

    private void loadRepositories()
    {
        Repository previous = selectedRepository();
        repositories.clear();
        repositories.addAll(RepositorySupport.allRepositories());
        repositories.sort(Comparator.comparing(repo -> repo.getWorkTree().getAbsolutePath()));
        repositoryCombo.removeAll();
        for (Repository repository : repositories)
            repositoryCombo.add(repository.getWorkTree().getName() + " — " //$NON-NLS-1$
                + repository.getWorkTree().getParent());
        GridData data = (GridData) repositoryCombo.getLayoutData();
        data.exclude = repositories.size() <= 1;
        repositoryCombo.setVisible(!data.exclude);
        Repository candidate = indexOf(previous) >= 0 ? previous : preferredRepository;
        if (indexOf(candidate) < 0)
            candidate = Repositories.context(getSite().getPage());
        if (indexOf(candidate) < 0)
            candidate = repositories.isEmpty() ? null : repositories.get(0);
        selectRepository(candidate);
    }

    private void selectionChanged(IWorkbenchPart part, ISelection selection)
    {
        if (part == this)
            return;
        Repository repository = Repositories.fromSelection(selection);
        if (repository != null)
        {
            if (indexOf(repository) < 0)
                loadRepositories();
            selectRepository(repository);
        }
    }

    private void selectRepository(Repository repository)
    {
        int index = indexOf(repository);
        if (index < 0)
            repositoryCombo.deselectAll();
        else
            repositoryCombo.select(index);
        selectionProvider.setSelection(index < 0 ? StructuredSelection.EMPTY
            : new StructuredSelection(repositories.get(index)));
        preferredRepository = selectedRepository();
        updateRepository();
    }

    private int indexOf(Repository repository)
    {
        if (repository != null)
            for (int i = 0; i < repositories.size(); i++)
                if (repositories.get(i).getDirectory().equals(repository.getDirectory()))
                    return i;
        return -1;
    }

    private Repository selectedRepository()
    {
        int index = repositoryCombo == null ? -1 : repositoryCombo.getSelectionIndex();
        return index < 0 || index >= repositories.size() ? null : repositories.get(index);
    }

    private void updateRepository()
    {
        Repository repository = selectedRepository();
        File directory = repository == null ? null : repository.getDirectory();
        if (!Objects.equals(directory, displayedDirectory))
        {
            displayedDirectory = directory;
            overview = new RepositoryOverview(0, -1, -1);
            changes = new WorkingChanges(List.of(), List.of());
            movedPath = null;
            fillChangesTree();
        }
        if (repository == null)
        {
            branchButton.setText(Messages.get("chooseRepository")); //$NON-NLS-1$
            hasRemote = false;
        }
        else
        {
            try
            {
                String branch = repository.getBranch();
                branchButton.setText(branch);
                BranchConfig config = new BranchConfig(repository.getConfig(), branch);
                hasRemote = config.getRemoteTrackingBranch() != null || repository.getConfig()
                    .getString("remote", "origin", "url") != null; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            catch (IOException e)
            {
                branchButton.setText(e.getMessage());
                hasRemote = false;
            }
        }
        branchButton.setEnabled(repository != null);
        countsLabel.setText(""); //$NON-NLS-1$
        updatePrimary();
        scheduleRefresh();
    }

    private void scheduleRefresh()
    {
        if (refreshJob != null)
            refreshJob.cancel();
        int current = ++generation;
        Repository repository = selectedRepository();
        if (repository == null || RUNNING.containsKey(repository.getDirectory()))
            return;
        refreshJob = new Job(Messages.get("statusReading")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor)
            {
                try
                {
                    WorkingChanges latestChanges = WorkingChanges.read(repository);
                    RepositoryOverview latestOverview = RepositoryOverview.read(repository);
                    onUi(() ->
                    {
                        if (instance == GitFlowView.this && current == generation)
                        {
                            changes = latestChanges;
                            overview = latestOverview;
                            fillChangesTree();
                            updatePrimary();
                        }
                    });
                }
                catch (Exception e)
                {
                    onUi(() ->
                    {
                        if (instance == GitFlowView.this && current == generation)
                            feedbackLabel.setText(Messages.get("statusUnavailable") + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                    });
                }
                return Status.OK_STATUS;
            }
        };
        refreshJob.setSystem(true);
        refreshJob.schedule(300);
    }

    private void fillChangesTree()
    {
        String selectedPath = movedPath;
        boolean selectedStaged = movedToStaged;
        if (selectedPath == null && changesTree.getSelectionCount() > 0)
        {
            TreeItem selected = changesTree.getSelection()[0];
            if (selected.getData() instanceof FileChange file)
            {
                selectedPath = file.path();
                selectedStaged = Boolean.TRUE.equals(selected.getParentItem().getData());
            }
        }
        TreeItem moved = null;
        changesTree.setRedraw(false);
        try
        {
            changesTree.removeAll();
            TreeItem staged = fillGroup(changes.staged(), true, stagedExpanded,
                selectedPath, selectedStaged);
            TreeItem unstaged = fillGroup(changes.unstaged(), false, unstagedExpanded,
                selectedPath, selectedStaged);
            moved = selectedStaged ? staged : unstaged;
            if (moved == null && selectedPath != null)
                for (TreeItem group : changesTree.getItems())
                    for (TreeItem item : group.getItems())
                        if (item.getData() instanceof FileChange file
                            && file.path().equals(selectedPath))
                            moved = item;
            movedPath = null;
        }
        finally
        {
            changesTree.setRedraw(true);
        }
        if (moved != null)
        {
            changesTree.setSelection(moved);
            changesTree.showItem(moved);
        }
        if (HISTORY.length() == 0)
            feedbackLabel.setText(changes.staged().isEmpty() && changes.unstaged().isEmpty()
                ? Messages.get("ready") : ""); //$NON-NLS-1$ //$NON-NLS-2$
        container.layout(true, true);
    }

    private TreeItem fillGroup(List<FileChange> files, boolean staged, boolean expanded,
        String selectedPath, boolean selectedStaged)
    {
        TreeItem moved = null;
        TreeItem group = new TreeItem(changesTree, SWT.NONE);
        group.setText(new String[] {
            Messages.get(staged ? "stagedChanges" : "unstagedChanges") + " · " + files.size(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            files.isEmpty() ? "" : staged ? "−" : "+"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        group.setData(staged);
        for (FileChange change : files)
        {
            TreeItem item = new TreeItem(group, SWT.NONE);
            item.setText(new String[] {change.state() + "  " + change.path(), staged ? "−" : "+"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            item.setData(change);
            if (staged == selectedStaged && change.path().equals(selectedPath))
                moved = item;
        }
        group.setExpanded(expanded);
        return moved;
    }

    private void updatePrimary()
    {
        Repository repository = selectedRepository();
        boolean busy = repository == null || RUNNING.containsKey(repository.getDirectory());
        boolean hasChanges = !changes.staged().isEmpty() || !changes.unstaged().isEmpty();
        if (hasChanges)
        {
            primaryButton.setText(sendAfterCommit && hasRemote ? Messages.get("commitAndPush") //$NON-NLS-1$
                : Messages.get("commitOnly")); //$NON-NLS-1$
            boolean tracked = changes.unstaged().stream().anyMatch(change -> !"U".equals(change.state())); //$NON-NLS-1$
            primaryButton.setEnabled(!busy && !messageField.getText().isBlank()
                && (!changes.staged().isEmpty() || tracked));
        }
        else if (hasRemote)
        {
            String counts = overview.incoming() > 0 || overview.outgoing() > 0
                ? " ↓" + overview.incoming() + " ↑" + overview.outgoing() : ""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            primaryButton.setText(Messages.get("syncChanges") + counts); //$NON-NLS-1$
            primaryButton.setEnabled(!busy);
        }
        else
        {
            primaryButton.setText(Messages.get("upToDate")); //$NON-NLS-1$
            primaryButton.setEnabled(false);
        }
        countsLabel.setText(overview.incoming() < 0 ? "" //$NON-NLS-1$
            : "↓" + overview.incoming() + " ↑" + overview.outgoing()); //$NON-NLS-1$ //$NON-NLS-2$
        container.layout(true, true);
    }

    private void runPrimary()
    {
        if (changes.staged().isEmpty() && changes.unstaged().isEmpty())
        {
            runSync();
            return;
        }
        Repository repository = selectedRepository();
        if (repository == null)
            return;
        boolean stageTracked = changes.staged().isEmpty();
        List<String> trackedFiles = changes.unstaged().stream()
            .filter(change -> !"U".equals(change.state())).map(FileChange::path).toList(); //$NON-NLS-1$
        String preview = String.join("\n", trackedFiles.stream().limit(8).toList()); //$NON-NLS-1$
        if (trackedFiles.size() > 8)
            preview += "\n… (" + (trackedFiles.size() - 8) + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        if (stageTracked && !MessageDialog.openQuestion(getSite().getShell(),
            Messages.get("commitOnly"), Messages.get("stageTrackedConfirm") + "\n\n" + preview)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return;
        String message = messageField.getText().trim();
        boolean send = sendAfterCommit && hasRemote;
        OperationJob.schedule(repository, getSite().getShell(), Messages.get("commitAndPush"), //$NON-NLS-1$
            (selected, monitor) -> CommitOperations.commitAndPush(selected, message,
                stageTracked, send, false, monitor),
            (selected, monitor) -> CommitOperations.commitAndPush(selected, message,
                stageTracked, send, true, monitor));
    }

    private void runSync()
    {
        Repository repository = selectedRepository();
        if (repository != null)
            OperationJob.schedule(repository, getSite().getShell(), Messages.get("syncChanges"), //$NON-NLS-1$
                PullOperations::smartPush,
                (selected, monitor) -> PullOperations.smartPush(selected, true, monitor));
    }

    private void runPull()
    {
        Repository repository = selectedRepository();
        if (repository != null)
            OperationJob.schedule(repository, getSite().getShell(), Messages.get("pullJob"), //$NON-NLS-1$
                PullOperations::smartPull,
                (selected, monitor) -> PullOperations.smartPull(selected, true, monitor));
    }

    private void changeFile(String path, boolean staged)
    {
        Repository repository = selectedRepository();
        if (repository != null && !isRunning(repository))
        {
            FileChange file = (staged ? changes.staged() : changes.unstaged()).stream()
                .filter(change -> change.path().equals(path)).findFirst().orElse(null);
            if (file == null)
                return;
            movedPath = path;
            movedToStaged = !staged;
            if (staged)
                unstagedExpanded = true;
            else
                stagedExpanded = true;
            optimisticMove(List.of(file), staged);
            OperationJob.schedule(repository, getSite().getShell(),
                Messages.get(staged ? "unstageFile" : "stageFile"), //$NON-NLS-1$ //$NON-NLS-2$
                (selected, monitor) -> staged ? WorkingChanges.unstage(selected, path)
                    : WorkingChanges.stage(selected, path), null);
        }
    }

    private void changeAll(boolean staged)
    {
        Repository repository = selectedRepository();
        if (repository == null || isRunning(repository))
            return;
        List<FileChange> files = List.copyOf(staged ? changes.staged() : changes.unstaged());
        if (files.isEmpty())
            return;
        movedPath = null;
        if (staged)
            unstagedExpanded = true;
        else
            stagedExpanded = true;
        optimisticMove(files, staged);
        OperationJob.schedule(repository, getSite().getShell(),
            Messages.get(staged ? "unstageAll" : "stageAll"), //$NON-NLS-1$ //$NON-NLS-2$
            (selected, monitor) ->
            {
                for (FileChange file : files)
                {
                    var result = staged ? WorkingChanges.unstage(selected, file.path())
                        : WorkingChanges.stage(selected, file.path());
                    if (!result.succeeded())
                        return result;
                }
                return new dev.edt.gitflow.core.OperationResult(
                    dev.edt.gitflow.core.OperationResult.Kind.SUCCESS,
                    (staged ? "Убрано из коммита: " : "Подготовлено: ") + files.size()); //$NON-NLS-1$ //$NON-NLS-2$
            }, null);
    }

    private void optimisticMove(List<FileChange> files, boolean staged)
    {
        List<FileChange> stagedFiles = new ArrayList<>(changes.staged());
        List<FileChange> unstagedFiles = new ArrayList<>(changes.unstaged());
        List<FileChange> source = staged ? stagedFiles : unstagedFiles;
        List<FileChange> target = staged ? unstagedFiles : stagedFiles;
        Set<String> paths = new HashSet<>();
        Set<String> targetPaths = new HashSet<>();
        for (FileChange file : files)
            paths.add(file.path());
        for (FileChange file : target)
            targetPaths.add(file.path());
        source.removeIf(change -> paths.contains(change.path()));
        for (FileChange file : files)
        {
            if (targetPaths.add(file.path()))
                target.add(new FileChange(file.path(), staged && "A".equals(file.state()) ? "U" //$NON-NLS-1$ //$NON-NLS-2$
                    : !staged && "U".equals(file.state()) ? "A" : file.state())); //$NON-NLS-1$ //$NON-NLS-2$
        }
        stagedFiles.sort(Comparator.comparing(FileChange::path));
        unstagedFiles.sort(Comparator.comparing(FileChange::path));
        changes = new WorkingChanges(List.copyOf(stagedFiles), List.copyOf(unstagedFiles));
        fillChangesTree();
        updatePrimary();
    }

    private void openDiff(String path, boolean staged)
    {
        Repository repository = selectedRepository();
        if (repository == null)
            return;
        File file = new File(repository.getWorkTree(), path);
        IFile[] workspaceFiles = ResourcesPlugin.getWorkspace().getRoot()
            .findFilesForLocationURI(file.toURI());
        if (workspaceFiles.length == 0)
        {
            publish(Messages.get("fileOutsideWorkspace") + " " + path); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        IFile workspaceFile = workspaceFiles[0];
        selectionProvider.setSelection(new StructuredSelection(workspaceFile));
        try
        {
            if (!"U".equals(findState(path, staged))) //$NON-NLS-1$
                getSite().getService(IHandlerService.class).executeCommand(staged
                    ? "org.eclipse.egit.ui.team.CompareIndexWithHead" //$NON-NLS-1$
                    : "org.eclipse.egit.ui.team.CompareWithIndex", null); //$NON-NLS-1$
            else
                IDE.openEditor(getSite().getPage(), workspaceFile);
        }
        catch (Exception e)
        {
            try
            {
                IDE.openEditor(getSite().getPage(), workspaceFile);
            }
            catch (PartInitException failure)
            {
                publish(Messages.get("openDiffFailed") + " " + failure.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    private String findState(String path, boolean staged)
    {
        return (staged ? changes.staged() : changes.unstaged()).stream()
            .filter(change -> change.path().equals(path)).map(FileChange::state).findFirst().orElse(""); //$NON-NLS-1$
    }

    @Override
    public void setFocus()
    {
        loadRepositories();
        messageField.setFocus();
    }

    @Override
    public void dispose()
    {
        getSite().getPage().removeSelectionListener(selectionListener);
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
        if (refreshJob != null)
            refreshJob.cancel();
        instance = null;
        super.dispose();
    }

    public static void useRepository(Repository repository)
    {
        preferredRepository = repository;
        onUi(() ->
        {
            if (instance != null)
                instance.selectRepository(repository);
        });
    }

    public static Repository repositoryForCommands()
    {
        return instance == null ? preferredRepository : instance.selectedRepository();
    }

    public static void started(Repository repository)
    {
        onUi(() ->
        {
            RUNNING.merge(repository.getDirectory(), 1, Integer::sum);
            if (instance != null)
            {
                ++instance.generation;
                if (instance.refreshJob != null)
                    instance.refreshJob.cancel();
                instance.updatePrimary();
            }
        });
    }

    public static boolean isRunning(Repository repository)
    {
        return RUNNING.containsKey(repository.getDirectory());
    }

    public static void finished(Repository repository)
    {
        onUi(() ->
        {
            RUNNING.computeIfPresent(repository.getDirectory(),
                (key, count) -> count == 1 ? null : count - 1);
            if (instance != null)
                instance.updateRepository();
        });
    }

    private static void onUi(Runnable runnable)
    {
        Display display = Display.getDefault();
        if (display.getThread() == Thread.currentThread())
            runnable.run();
        else
            display.asyncExec(runnable);
    }

    public static void publish(String message)
    {
        Display display = Display.getDefault();
        if (display.getThread() != Thread.currentThread())
        {
            display.asyncExec(() -> publish(message));
            return;
        }
        HISTORY.append('[').append(TIME.format(LocalTime.now())).append("] ") //$NON-NLS-1$
            .append(message).append(System.lineSeparator());
        if (HISTORY.length() > 30000)
            HISTORY.delete(0, HISTORY.length() - 20000);
        if (instance == null)
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            if (page == null)
                return;
            try
            {
                page.showView(ID, null, IWorkbenchPage.VIEW_VISIBLE);
            }
            catch (PartInitException e)
            {
                Platform.getLog(Platform.getBundle(PLUGIN_ID)).log(
                    new Status(IStatus.ERROR, PLUGIN_ID, e.getMessage(), e));
            }
        }
        if (instance != null && instance.feedbackLabel != null && !instance.feedbackLabel.isDisposed())
        {
            instance.feedbackLabel.setText(message);
            instance.container.layout(true, true);
        }
    }

    private static final class RepositorySelection implements ISelectionProvider
    {
        private final List<ISelectionChangedListener> listeners = new ArrayList<>();
        private ISelection selection = StructuredSelection.EMPTY;

        @Override
        public void addSelectionChangedListener(ISelectionChangedListener listener)
        {
            listeners.add(listener);
        }

        @Override
        public void removeSelectionChangedListener(ISelectionChangedListener listener)
        {
            listeners.remove(listener);
        }

        @Override
        public ISelection getSelection()
        {
            return selection;
        }

        @Override
        public void setSelection(ISelection value)
        {
            selection = value;
            SelectionChangedEvent event = new SelectionChangedEvent(this, value);
            for (ISelectionChangedListener listener : List.copyOf(listeners))
                listener.selectionChanged(event);
        }
    }
}
