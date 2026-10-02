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
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
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
import dev.edt.gitflow.core.OperationResult;
import dev.edt.gitflow.core.PullOperations;
import dev.edt.gitflow.core.RecentHistory;
import dev.edt.gitflow.core.RepositoryOverview;
import dev.edt.gitflow.core.RepositorySupport;
import dev.edt.gitflow.core.StashOperations;
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
    private Composite container;
    private Combo repositoryCombo;
    private Button branchButton;
    private Button syncButton;
    private Label countsLabel;
    private Text messageField;
    private RepositoryState repositoryState = RepositoryState.SAFE;
    private String autoFilledMergeMessage;
    private boolean mergeMessageEdited;
    private boolean settingMergeMessage;
    private Button primaryButton;
    private Button settingsButton;
    private Button historyButton;
    private Tree changesTree;
    private RecentHistoryPane historyPane;
    private ObjectId historyHead;
    private boolean stagedExpanded = true;
    private boolean unstagedExpanded = true;
    private String movedPath;
    private boolean movedToStaged;
    private CLabel feedbackLabel;
    private Job refreshJob;
    private boolean buildInProgress;
    private int generation;
    private File displayedDirectory;
    private boolean hasRemote;
    private boolean sendAfterCommit;
    private RepositoryOverview overview = new RepositoryOverview(0, -1, -1);
    private WorkingChanges changes = new WorkingChanges(List.of(), List.of());
    private final IResourceChangeListener resourceListener = event ->
    {
        int type = event.getType();
        onUi(() ->
        {
            if (instance != this)
                return;
            if (type == IResourceChangeEvent.PRE_BUILD)
            {
                buildInProgress = true;
                ++generation;
                if (refreshJob != null)
                    refreshJob.cancel();
            }
            else if (type == IResourceChangeEvent.POST_BUILD)
            {
                buildInProgress = false;
                scheduleRefresh();
            }
            else if (!buildInProgress)
                scheduleRefresh();
        });
    };

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
        syncButton = new Button(header, SWT.PUSH);
        syncButton.setText("⟳"); //$NON-NLS-1$
        syncButton.setToolTipText(Messages.get("fetchHint")); //$NON-NLS-1$
        syncButton.addListener(SWT.Selection, event -> runAdaptiveSync());

        repositoryCombo = new Combo(parent, SWT.DROP_DOWN | SWT.READ_ONLY);
        repositoryCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        repositoryCombo.addListener(SWT.Selection, event -> selectRepository(selectedRepository()));

        Label messageLabel = new Label(parent, SWT.NONE);
        messageLabel.setText(Messages.get("commitMessage")); //$NON-NLS-1$
        messageField = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData messageData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        messageData.heightHint = messageField.getLineHeight() * 2 + 8;
        messageField.setLayoutData(messageData);
        messageField.setToolTipText(Messages.get("commitShortcutHint")); //$NON-NLS-1$
        messageField.addModifyListener(event ->
        {
            if (!settingMergeMessage && repositoryState == RepositoryState.MERGING_RESOLVED)
                mergeMessageEdited = true;
            updatePrimary();
        });
        messageField.addListener(SWT.KeyDown, event ->
        {
            if (event.keyCode == SWT.CR && (event.stateMask & SWT.MOD1) != 0)
            {
                event.doit = false;
                if (primaryButton.isEnabled())
                    runPrimary();
            }
        });
        Composite primaryRow = new Composite(parent, SWT.NONE);
        primaryRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout primaryLayout = new GridLayout(2, false);
        primaryLayout.marginWidth = 0;
        primaryLayout.marginHeight = 0;
        primaryLayout.horizontalSpacing = 4;
        primaryRow.setLayout(primaryLayout);
        primaryButton = new Button(primaryRow, SWT.PUSH);
        primaryButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        primaryButton.addListener(SWT.Selection, event -> runPrimary());
        settingsButton = new Button(primaryRow, SWT.PUSH);
        settingsButton.setText("⚙"); //$NON-NLS-1$
        settingsButton.setToolTipText(Messages.get("settings")); //$NON-NLS-1$
        settingsButton.setLayoutData(new GridData(SWT.CENTER, SWT.CENTER, false, false));
        settingsButton.addListener(SWT.Selection, event -> showSettingsMenu(settingsButton));

        SashForm content = new SashForm(parent, SWT.VERTICAL);
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        content.setSashWidth(4);
        changesTree = changeTree(content);
        historyPane = new RecentHistoryPane(content);
        content.setWeights(70, 30);
        Composite footer = new Composite(parent, SWT.NONE);
        footer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout footerLayout = new GridLayout(2, false);
        footerLayout.marginWidth = 0;
        footerLayout.marginHeight = 0;
        footerLayout.horizontalSpacing = 4;
        footer.setLayout(footerLayout);
        feedbackLabel = new CLabel(footer, SWT.NONE);
        GridData feedbackData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        feedbackData.widthHint = 0;
        feedbackLabel.setLayoutData(feedbackData);
        feedbackLabel.setText(Messages.get("ready")); //$NON-NLS-1$
        historyButton = new Button(footer, SWT.PUSH);
        historyButton.setText("◷"); //$NON-NLS-1$
        historyButton.setToolTipText(Messages.get("showHistory")); //$NON-NLS-1$
        historyButton.addListener(SWT.Selection, event -> showHistory());

        fillChangesTree();
        loadRepositories();
        getSite().setSelectionProvider(selectionProvider);
        getSite().getPage().addSelectionListener(selectionListener);
        ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener,
            IResourceChangeEvent.POST_CHANGE | IResourceChangeEvent.PRE_BUILD
                | IResourceChangeEvent.POST_BUILD);
    }

    private Tree changeTree(Composite parent)
    {
        Tree tree = new Tree(parent, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER | SWT.V_SCROLL);
        GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
        data.heightHint = 140;
        tree.setLayoutData(data);
        TreeColumn file = new TreeColumn(tree, SWT.LEFT);
        file.setWidth(260);
        TreeColumn discard = new TreeColumn(tree, SWT.CENTER);
        discard.setWidth(32);
        TreeColumn action = new TreeColumn(tree, SWT.CENTER);
        action.setWidth(32);
        tree.addListener(SWT.Resize, event ->
            file.setWidth(Math.max(100, tree.getClientArea().width
                - discard.getWidth() - action.getWidth() - 3)));
        tree.addListener(SWT.Expand, event -> rememberExpansion((TreeItem) event.item, true));
        tree.addListener(SWT.Collapse, event -> rememberExpansion((TreeItem) event.item, false));
        tree.addListener(SWT.MouseMove, event ->
        {
            TreeItem item = tree.getItem(new Point(event.x, event.y));
            if (item == null)
                tree.setToolTipText(null);
            else if (item.getBounds(1).contains(event.x, event.y)
                && (canDiscard(item) || isChangeGroup(item) && canDiscardAllChanges()))
                tree.setToolTipText(Messages.get(isChangeGroup(item)
                    ? "discardAllChanges" : "discardChanges")); //$NON-NLS-1$
            else if (item.getBounds(2).contains(event.x, event.y))
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
            if (event.button != 1)
                return;
            TreeItem item = tree.getItem(new Point(event.x, event.y));
            if (item == null)
                return;
            if (item.getBounds(1).contains(event.x, event.y)
                && isChangeGroup(item) && canDiscardAllChanges())
                discardAllChanges();
            else if (item.getBounds(1).contains(event.x, event.y) && canDiscard(item))
                discardFile((FileChange) item.getData());
            else if (item.getBounds(2).contains(event.x, event.y))
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
            else if (event.keyCode == SWT.DEL && canDiscard(selected[0]))
                discardFile((FileChange) selected[0].getData());
        });
        Menu menu = new Menu(tree);
        tree.setMenu(menu);
        tree.addListener(SWT.MenuDetect, event ->
        {
            TreeItem item = tree.getItem(tree.toControl(event.x, event.y));
            if (item == null && tree.getSelectionCount() > 0)
                item = tree.getSelection()[0];
            if (item == null || item.getData() instanceof Boolean && item.getItemCount() == 0
                && !(isChangeGroup(item) && (canDiscardAllChanges() || hasChanges())))
                event.doit = false;
            else
                tree.setSelection(item);
        });
        menu.addListener(SWT.Show, event ->
        {
            for (MenuItem item : menu.getItems())
                item.dispose();
            if (tree.getSelectionCount() == 0)
                return;
            TreeItem item = tree.getSelection()[0];
            Repository repository = selectedRepository();
            boolean busy = repository == null || isRunning(repository);
            if (item.getData() instanceof Boolean staged)
            {
                boolean added = false;
                if (canDiscardAllChanges())
                {
                    menuItem(menu, Messages.get("discardAllChanges"), this::discardAllChanges) //$NON-NLS-1$
                        .setEnabled(!busy);
                    added = true;
                }
                if (item.getItemCount() > 0)
                {
                    if (added)
                        new MenuItem(menu, SWT.SEPARATOR);
                    menuItem(menu, Messages.get(staged ? "unstageAll" : "stageAll"), //$NON-NLS-1$ //$NON-NLS-2$
                        () -> changeAll(staged)).setEnabled(!busy);
                    added = true;
                }
                if (hasChanges())
                {
                    if (added)
                        new MenuItem(menu, SWT.SEPARATOR);
                    menuItem(menu, Messages.get("hideAllChanges"), this::hideChanges) //$NON-NLS-1$
                        .setEnabled(!busy);
                }
            }
            else if (item.getData() instanceof FileChange change)
            {
                boolean staged = Boolean.TRUE.equals(item.getParentItem().getData());
                menuItem(menu, Messages.get("openChanges"), () -> openDiff(change.path(), staged)); //$NON-NLS-1$
                MenuItem open = menuItem(menu, Messages.get("openFile"), () -> openFile(change.path())); //$NON-NLS-1$
                open.setEnabled(workspaceFile(change.path()) != null && !"D".equals(change.state())); //$NON-NLS-1$
                new MenuItem(menu, SWT.SEPARATOR);
                MenuItem history = menuItem(menu, Messages.get("fileHistory"), //$NON-NLS-1$
                    () -> openFileHistory(change.path()));
                history.setEnabled(workspaceFile(change.path()) != null
                    && !"U".equals(change.state()) && !"A".equals(change.state()) //$NON-NLS-1$ //$NON-NLS-2$
                    && changes.staged().stream().noneMatch(entry -> entry.path().equals(change.path())
                        && "A".equals(entry.state()))); //$NON-NLS-1$
                new MenuItem(menu, SWT.SEPARATOR);
                boolean added = false;
                if (canDiscard(item))
                {
                    menuItem(menu, Messages.get("discardChanges"), //$NON-NLS-1$
                        () -> discardFile(change)).setEnabled(!busy);
                    added = true;
                }
                menuItem(menu, Messages.get(staged ? "unstageFile" : "stageFile"), //$NON-NLS-1$ //$NON-NLS-2$
                    () -> changeFile(change.path(), staged)).setEnabled(!busy);
                if (added)
                    new MenuItem(menu, SWT.SEPARATOR);
                menuItem(menu, Messages.get("hideChanges"), this::hideChanges) //$NON-NLS-1$
                    .setEnabled(!busy);
            }
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

    private boolean canDiscard(TreeItem item)
    {
        return item.getData() instanceof FileChange change && canDiscard(change);
    }

    private boolean canDiscard(FileChange change)
    {
        return ("M".equals(change.state()) || "D".equals(change.state())) //$NON-NLS-1$ //$NON-NLS-2$
            && changes.staged().stream().noneMatch(file -> file.path().equals(change.path())
                && "A".equals(file.state())); //$NON-NLS-1$
    }

    private static boolean isChangeGroup(TreeItem item)
    {
        return item.getData() instanceof Boolean;
    }

    private boolean hasChanges()
    {
        return !changes.staged().isEmpty() || !changes.unstaged().isEmpty();
    }

    private boolean canDiscardAllChanges()
    {
        for (FileChange change : changes.staged())
            if ("M".equals(change.state()) || "D".equals(change.state()) || "A".equals(change.state())) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                return true;
        for (FileChange change : changes.unstaged())
            if ("M".equals(change.state()) || "D".equals(change.state())) //$NON-NLS-1$ //$NON-NLS-2$
                return true;
        return false;
    }

    private int discardableChangeCount()
    {
        Set<String> paths = new HashSet<>();
        for (FileChange change : changes.staged())
            if ("M".equals(change.state()) || "D".equals(change.state()) || "A".equals(change.state())) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                paths.add(change.path());
        for (FileChange change : changes.unstaged())
            if ("M".equals(change.state()) || "D".equals(change.state())) //$NON-NLS-1$ //$NON-NLS-2$
                paths.add(change.path());
        return paths.size();
    }

    private void showSettingsMenu(Button anchor)
    {
        Menu menu = new Menu(anchor);
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
        menu.addListener(SWT.Hide, event -> anchor.getDisplay().asyncExec(menu::dispose));
        menu.setLocation(anchor.toDisplay(0, anchor.getSize().y));
        menu.setVisible(true);
    }

    private void showHistory()
    {
        String history = HISTORY.length() == 0 ? Messages.get("historyEmpty") : HISTORY.toString(); //$NON-NLS-1$
        Dialog dialog = new Dialog(getSite().getShell())
        {
            @Override
            protected Control createDialogArea(Composite parent)
            {
                Composite area = (Composite) super.createDialogArea(parent);
                Text text = new Text(area, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
                GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
                data.widthHint = 720;
                data.heightHint = 400;
                text.setLayoutData(data);
                text.setText(history);
                text.selectAll();
                return area;
            }

            @Override
            protected void configureShell(Shell shell)
            {
                super.configureShell(shell);
                shell.setText(Messages.get("showHistory")); //$NON-NLS-1$
            }
        };
        dialog.open();
    }

    private static MenuItem menuItem(Menu menu, String title, Runnable action)
    {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(title);
        item.addListener(SWT.Selection, event -> action.run());
        return item;
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
            if (autoFilledMergeMessage != null
                && messageField.getText().equals(autoFilledMergeMessage))
                messageField.setText(""); //$NON-NLS-1$
            autoFilledMergeMessage = null;
            mergeMessageEdited = false;
            repositoryState = RepositoryState.SAFE;
            displayedDirectory = directory;
            overview = new RepositoryOverview(0, -1, -1);
            changes = new WorkingChanges(List.of(), List.of());
            historyHead = null;
            historyPane.setCommits(List.of());
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
        if (buildInProgress || repository == null || RUNNING.containsKey(repository.getDirectory()))
            return;
        ObjectId displayedHead = historyHead;
        refreshJob = new Job(Messages.get("statusReading")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor)
            {
                try
                {
                    WorkingChanges latestChanges = WorkingChanges.read(repository);
                    RepositoryOverview latestOverview = RepositoryOverview.read(repository, latestChanges);
                    RepositoryState latestState = repository.getRepositoryState();
                    String mergeMessage = latestState == RepositoryState.MERGING_RESOLVED
                        ? repository.readMergeCommitMsg() : null;
                    ObjectId latestHead = repository.resolve(Constants.HEAD);
                    List<RecentHistory.Entry> latestHistory = Objects.equals(latestHead, displayedHead)
                        ? null : RecentHistory.read(repository, 30);
                    onUi(() ->
                    {
                        if (instance == GitFlowView.this && current == generation)
                        {
                            changes = latestChanges;
                            overview = latestOverview;
                            updateMergeMessage(latestState, mergeMessage);
                            if (latestHistory != null)
                            {
                                historyHead = latestHead;
                                historyPane.setCommits(latestHistory);
                            }
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
                            showFeedback(Messages.get("statusUnavailable") + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                    });
                }
                return Status.OK_STATUS;
            }
        };
        refreshJob.setSystem(true);
        refreshJob.schedule(300);
    }

    private void updateMergeMessage(RepositoryState state, String mergeMessage)
    {
        if (state == RepositoryState.MERGING_RESOLVED && mergeMessage != null
            && !mergeMessage.isBlank() && !mergeMessageEdited
            && (messageField.getText().isBlank()
                || messageField.getText().equals(autoFilledMergeMessage)))
        {
            String text = mergeMessage.replace("\r\n", "\n").replace("\n", Text.DELIMITER); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (!messageField.getText().equals(text))
            {
                settingMergeMessage = true;
                messageField.setText(text);
                settingMergeMessage = false;
            }
            autoFilledMergeMessage = messageField.getText();
        }
        else if (state != RepositoryState.MERGING_RESOLVED)
        {
            if (autoFilledMergeMessage != null
                && messageField.getText().equals(autoFilledMergeMessage))
            {
                settingMergeMessage = true;
                messageField.setText(""); //$NON-NLS-1$
                settingMergeMessage = false;
            }
            autoFilledMergeMessage = null;
            mergeMessageEdited = false;
        }
        repositoryState = state;
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
            showFeedback(changes.staged().isEmpty() && changes.unstaged().isEmpty()
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
            canDiscardAllChanges() ? "↶" : "", //$NON-NLS-1$ //$NON-NLS-2$
            files.isEmpty() ? "" : staged ? "−" : "+"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        group.setData(staged);
        for (FileChange change : files)
        {
            TreeItem item = new TreeItem(group, SWT.NONE);
            item.setText(new String[] {change.state() + "  " + change.path(),
                canDiscard(change)
                    ? "↶" : "", staged ? "−" : "+"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
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
        boolean mergeReady = repositoryState == RepositoryState.MERGING_RESOLVED;
        if (hasChanges || mergeReady)
        {
            primaryButton.setText(sendAfterCommit && hasRemote ? Messages.get("commitAndPush") //$NON-NLS-1$
                : Messages.get("commitOnly")); //$NON-NLS-1$
            boolean tracked = changes.unstaged().stream().anyMatch(change -> !"U".equals(change.state())); //$NON-NLS-1$
            primaryButton.setEnabled(!busy && !messageField.getText().isBlank()
                && (mergeReady || repositoryState == RepositoryState.SAFE
                    && (!changes.staged().isEmpty() || tracked)));
        }
        else
        {
            primaryButton.setText(Messages.get("upToDate")); //$NON-NLS-1$
            primaryButton.setEnabled(false);
        }
        countsLabel.setText(overview.incoming() < 0 ? "" //$NON-NLS-1$
            : "↓" + overview.incoming() + " ↑" + overview.outgoing()); //$NON-NLS-1$ //$NON-NLS-2$
        syncButton.setText(syncButtonText());
        syncButton.setToolTipText(syncTooltip());
        syncButton.setEnabled(repository != null && hasRemote && !busy
            && repositoryState == RepositoryState.SAFE);
        container.layout(true, true);
    }

    private String syncTooltip()
    {
        if (overview.incoming() > 0 && overview.outgoing() == 0)
            return Messages.get("smartPullHint"); //$NON-NLS-1$
        if (overview.outgoing() > 0 && overview.incoming() == 0)
            return Messages.get("smartPushHint"); //$NON-NLS-1$
        if (overview.incoming() > 0 && overview.outgoing() > 0)
            return Messages.get("smartSyncHint"); //$NON-NLS-1$
        return Messages.get("fetchHint"); //$NON-NLS-1$
    }

    private String syncButtonText()
    {
        String action;
        String counts = ""; //$NON-NLS-1$
        if (overview.incoming() > 0 && overview.outgoing() == 0)
        {
            action = Messages.get("syncPullAction"); //$NON-NLS-1$
            counts = " " + overview.incoming() + "↓"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (overview.outgoing() > 0 && overview.incoming() == 0)
        {
            action = Messages.get("syncPushAction"); //$NON-NLS-1$
            counts = " " + overview.outgoing() + "↑"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (overview.incoming() > 0 && overview.outgoing() > 0)
        {
            action = Messages.get("syncBothAction"); //$NON-NLS-1$
            counts = " " + overview.incoming() + "↓ " + overview.outgoing() + "↑"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
            action = Messages.get("syncIdleAction"); //$NON-NLS-1$
        return "⟳ " + action + counts; //$NON-NLS-1$
    }

    private void runAdaptiveSync()
    {
        if (overview.incoming() <= 0 && overview.outgoing() <= 0)
            runFetch();
        else if (overview.incoming() > 0 && overview.outgoing() == 0)
            runPull();
        else
            runSync();
    }

    private void runFetch()
    {
        Repository repository = selectedRepository();
        if (repository != null)
            OperationJob.schedule(repository, getSite().getShell(), Messages.get("fetchJob"),
                PullOperations::fetch, null); //$NON-NLS-1$
    }

    private void discardAllChanges()
    {
        Repository repository = selectedRepository();
        if (repository == null || isRunning(repository) || !canDiscardAllChanges())
            return;
        String question = Messages.get("discardAllConfirm") + "\n\n" //$NON-NLS-1$
            + Messages.get("discardableChangeCount") + " " + discardableChangeCount(); //$NON-NLS-1$
        if (!MessageDialog.openConfirm(getSite().getShell(),
            Messages.get("discardAllChanges"), question)) //$NON-NLS-1$
            return;
        OperationJob.schedule(repository, getSite().getShell(), Messages.get("discardAllChanges"), //$NON-NLS-1$
            (selected, monitor) -> WorkingChanges.resetAllTrackedToHead(selected), null);
    }

    private void runPrimary()
    {
        boolean mergeReady = repositoryState == RepositoryState.MERGING_RESOLVED;
        if (!mergeReady && changes.staged().isEmpty() && changes.unstaged().isEmpty())
        {
            runSync();
            return;
        }
        Repository repository = selectedRepository();
        if (repository == null)
            return;
        boolean stageTracked = !mergeReady && changes.staged().isEmpty();
        List<String> trackedFiles = changes.unstaged().stream()
            .filter(change -> !"U".equals(change.state())).map(FileChange::path).toList(); //$NON-NLS-1$
        String preview = String.join("\n", trackedFiles.stream().limit(8).toList()); //$NON-NLS-1$
        if (trackedFiles.size() > 8)
            preview += "\n… (" + (trackedFiles.size() - 8) + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        if (stageTracked && !MessageDialog.openQuestion(getSite().getShell(),
            Messages.get("commitOnly"), Messages.get("stageTrackedConfirm") + "\n\n" + preview)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return;
        String message = messageField.getText().replace("\r\n", "\n").trim(); //$NON-NLS-1$ //$NON-NLS-2$
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

    private void discardFile(FileChange file)
    {
        Repository repository = selectedRepository();
        if (repository == null || isRunning(repository))
            return;
        String question = Messages.get("discardConfirm") + "\n\n" + file.path() + "\n\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + Messages.get("discardWarning"); //$NON-NLS-1$
        if (!MessageDialog.openConfirm(getSite().getShell(),
            Messages.get("discardChanges"), question)) //$NON-NLS-1$
            return;
        movedPath = null;
        changes = new WorkingChanges(changes.staged().stream()
            .filter(change -> !change.path().equals(file.path())).toList(),
            changes.unstaged().stream()
                .filter(change -> !change.path().equals(file.path())).toList());
        fillChangesTree();
        updatePrimary();
        OperationJob.schedule(repository, getSite().getShell(), Messages.get("discardChanges"), //$NON-NLS-1$
            (selected, monitor) -> WorkingChanges.resetFileToHead(selected, file.path()), null);
    }

    private void openFile(String path)
    {
        IFile file = workspaceFile(path);
        if (file == null)
        {
            publish(Messages.get("fileOutsideWorkspace") + " " + path); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        try
        {
            IDE.openEditor(getSite().getPage(), file);
        }
        catch (PartInitException e)
        {
            publish(Messages.get("openFileFailed") + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void openFileHistory(String path)
    {
        IFile file = workspaceFile(path);
        if (file == null)
        {
            publish(Messages.get("fileOutsideWorkspace") + " " + path); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        selectionProvider.setSelection(new StructuredSelection(file));
        try
        {
            getSite().getService(IHandlerService.class)
                .executeCommand("org.eclipse.egit.ui.team.ShowHistory", null); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            publish(Messages.get("fileHistoryFailed") + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private IFile workspaceFile(String path)
    {
        Repository repository = selectedRepository();
        if (repository == null)
            return null;
        File file = new File(repository.getWorkTree(), path);
        IFile[] workspaceFiles = ResourcesPlugin.getWorkspace().getRoot()
            .findFilesForLocationURI(file.toURI());
        return workspaceFiles.length == 0 ? null : workspaceFiles[0];
    }

    private void hideChanges()
    {
        Repository repository = selectedRepository();
        if (repository == null || isRunning(repository))
            return;
        OperationJob.schedule(repository, getSite().getShell(), Messages.get("hideChanges"), //$NON-NLS-1$
            (selected, monitor) ->
            {
                if (!RepositorySupport.isSafe(selected))
                    return new OperationResult(OperationResult.Kind.ERROR, Messages.get("busy")); //$NON-NLS-1$
                StashOperations.Result result = StashOperations.quickStash(selected, true, monitor);
                return switch (result.outcome())
                {
                    case CREATED -> new OperationResult(OperationResult.Kind.SUCCESS,
                        Messages.get("created") + result.detail() + Messages.get("createdEnd")); //$NON-NLS-1$ //$NON-NLS-2$
                    case NO_CHANGES -> new OperationResult(OperationResult.Kind.NO_CHANGE,
                        Messages.get("noChanges")); //$NON-NLS-1$
                    case APPLIED, NOT_FOUND, CONFLICTS, ERROR ->
                        new OperationResult(OperationResult.Kind.ERROR, result.detail());
                };
            }, null);
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
            instance.showFeedback(message);
        }
    }

    private void showFeedback(String message)
    {
        String summary = message;
        if (message.startsWith(Messages.get("openedLink"))) //$NON-NLS-1$
            summary = Messages.get("linkOpenedSummary"); //$NON-NLS-1$
        else if (message.startsWith(Messages.get("copiedLink"))) //$NON-NLS-1$
            summary = Messages.get("linkCopiedSummary"); //$NON-NLS-1$
        else if (message.indexOf('\n') >= 0)
            summary = message.substring(0, message.indexOf('\n')) + "…"; //$NON-NLS-1$
        feedbackLabel.setText(summary);
        feedbackLabel.setToolTipText(summary.equals(message) ? null : message);
        container.layout(true, true);
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
