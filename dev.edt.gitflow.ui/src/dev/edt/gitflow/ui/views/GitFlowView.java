package dev.edt.gitflow.ui.views;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.wizard.WizardDialog;
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
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import dev.edt.gitflow.core.GitLinks;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffCache;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffCacheEntry;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffChangedListener;
import org.eclipse.egit.core.internal.indexdiff.IndexDiffData;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
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
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.ISources;
import org.eclipse.ui.commands.ICommandService;
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
import dev.edt.gitflow.ui.handlers.OpenLinkHandler;
import dev.edt.gitflow.ui.handlers.OperationJob;
import dev.edt.gitflow.ui.handlers.Repositories;
import dev.edt.gitflow.ui.handlers.SmartCheckoutHandler;
import org.eclipse.egit.ui.internal.branch.BranchOperationUI;
import org.eclipse.egit.ui.internal.CompareUtils;
import org.eclipse.egit.ui.internal.merge.GitMergeEditorInput;
import org.eclipse.egit.ui.internal.merge.MergeInputMode;
import org.eclipse.egit.ui.internal.revision.GitCompareFileRevisionEditorInput;
import org.eclipse.egit.ui.internal.synchronize.compare.LocalNonWorkspaceTypedElement;
import org.eclipse.egit.ui.internal.actions.ResetMenu;
import org.eclipse.egit.ui.internal.dialogs.CompareTreeView;
import org.eclipse.egit.ui.internal.dialogs.CreateTagDialog;
import org.eclipse.egit.ui.internal.repository.CreateBranchWizard;
import org.eclipse.egit.ui.internal.commit.RepositoryCommit;
import org.eclipse.egit.ui.internal.commit.CommitEditor;
import org.eclipse.egit.ui.internal.commit.command.CherryPickUI;
import org.eclipse.egit.core.op.TagOperation;
import org.eclipse.egit.core.internal.credentials.EGitCredentialsProvider;
import org.eclipse.egit.ui.internal.push.PushTagsWizard;

public class GitFlowView extends ViewPart
{
    public static final String ID = "dev.edt.gitflow.ui.view.operations"; //$NON-NLS-1$
    private static final String PLUGIN_ID = "dev.edt.gitflow.ui"; //$NON-NLS-1$
    private static final int HISTORY_PAGE_SIZE = 30;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$
    private static final StringBuilder HISTORY = new StringBuilder();
    private static final Map<File, Integer> RUNNING = new HashMap<>();
    private static GitFlowView instance;
    private static Repository preferredRepository;

    private final List<Repository> repositories = new ArrayList<>();
    private final Map<String, Image> metadataImages = new HashMap<>();
    private final Deque<PendingIndexChange> queuedIndexChanges = new ArrayDeque<>();
    private final RepositorySelection selectionProvider = new RepositorySelection();
    private final ISelectionListener selectionListener = this::selectionChanged;
    private Composite container;
    private Combo repositoryCombo;
    private Button branchButton;
    private Image branchImage;
    private Button syncButton;
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
    private String historyStateKey;
    private final List<RecentHistory.Entry> historyEntries = new ArrayList<>();
    private boolean historyHasMore;
    private boolean historyLoading;
    private boolean stagedExpanded = true;
    private boolean unstagedExpanded = true;
    private boolean hasStashedChanges;
    private String movedPath;
    private boolean movedToStaged;
    private CLabel feedbackLabel;
    private Job refreshJob;
    private Repository indexObservedRepository;
    private IndexDiffCacheEntry indexDiffEntry;
    private volatile IndexDiffData indexDiffData;
    private final IndexDiffChangedListener indexDiffListener = this::indexDiffChanged;
    private boolean refreshAgain;
    private boolean messageSplitInitialized;
    private int generation;
    private File displayedDirectory;
    private ObjectId overviewHead;
    private ObjectId overviewUpstream;
    private boolean hasRemote;
    private boolean sendAfterCommit;
    private boolean indexChangeRunning;
    private RepositoryOverview overview = new RepositoryOverview(0, -1, -1);
    private WorkingChanges changes = new WorkingChanges(List.of(), List.of());
    private final IResourceChangeListener resourceListener = event ->
    {
        onUi(() ->
        {
            if (instance != this)
                return;
            if (indexDiffEntry == null && affectsRepositories(event.getDelta()))
                scheduleRefresh(REFRESH_QUICK_DELAY);
        });
    };

    private boolean affectsRepositories(IResourceDelta delta)
    {
        if (delta == null || repositories.isEmpty())
            return true;
        List<IPath> roots = new ArrayList<>();
        for (Repository repository : repositories)
            roots.add(new Path(repository.getWorkTree().getAbsolutePath()));
        boolean[] found = new boolean[1];
        try
        {
            delta.accept(visited ->
            {
                if (found[0])
                    return false;
                IPath location = visited.getResource().getLocation();
                if (location == null)
                {
                    found[0] = true;
                    return false;
                }
                for (IPath root : roots)
                    if (root.isPrefixOf(location))
                    {
                        found[0] = true;
                        return false;
                    }
                return true;
            });
        }
        catch (CoreException e)
        {
            return true;
        }
        return found[0];
    }

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
        GridLayout headerLayout = new GridLayout(2, false);
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
        branchImage = egitImage("icons/obj16/branch_obj.png").createImage(); //$NON-NLS-1$
        branchButton.setImage(branchImage);
        syncButton = new Button(header, SWT.PUSH);
        syncButton.setText("↻"); //$NON-NLS-1$
        syncButton.setToolTipText(Messages.get("fetchHint")); //$NON-NLS-1$
        syncButton.addListener(SWT.Selection, event -> runAdaptiveSync());

        repositoryCombo = new Combo(parent, SWT.DROP_DOWN | SWT.READ_ONLY);
        repositoryCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        repositoryCombo.addListener(SWT.Selection, event -> selectRepository(selectedRepository()));

        SashForm messageSplit = new SashForm(parent, SWT.VERTICAL);
        messageSplit.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        messageSplit.setSashWidth(4);
        Composite messageArea = new Composite(messageSplit, SWT.NONE);
        GridLayout messageLayout = new GridLayout(1, false);
        messageLayout.marginWidth = 0;
        messageLayout.marginHeight = 0;
        messageArea.setLayout(messageLayout);
        messageField = new Text(messageArea, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        messageField.setMessage(Messages.get("commitMessage")); //$NON-NLS-1$
        GridData messageData = new GridData(SWT.FILL, SWT.FILL, true, true);
        messageData.heightHint = messageField.getLineHeight() * 2 + 8;
        messageField.setLayoutData(messageData);
        messageField.setToolTipText(Messages.get("commitShortcutHint")); //$NON-NLS-1$
        messageField.addModifyListener(event ->
        {
            if (!settingMergeMessage && repositoryState == RepositoryState.MERGING_RESOLVED)
                mergeMessageEdited = true;
            updatePrimary();
            syncStagingMessage(true);
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
        Composite lower = new Composite(messageSplit, SWT.NONE);
        GridLayout lowerLayout = new GridLayout(1, false);
        lowerLayout.marginWidth = 0;
        lowerLayout.marginHeight = 0;
        lowerLayout.verticalSpacing = 7;
        lower.setLayout(lowerLayout);
        Composite primaryRow = new Composite(lower, SWT.NONE);
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
        SashForm content = new SashForm(lower, SWT.VERTICAL);
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        content.setSashWidth(4);
        changesTree = changeTree(content);
        historyPane = new RecentHistoryPane(content);
        historyPane.setMenuProvider(this::historyMenu);
        content.setWeights(70, 30);
        messageSplit.addListener(SWT.Resize, event ->
        {
            Rectangle area = parent.getClientArea();
            boolean horizontal = area.width > area.height * 3 / 2;
            int orientation = horizontal ? SWT.HORIZONTAL : SWT.VERTICAL;
            if (messageSplit.getOrientation() != orientation)
            {
                messageSplit.setOrientation(orientation);
                content.setOrientation(orientation);
                Composite controlsParent = horizontal ? messageArea : parent;
                header.setParent(controlsParent);
                repositoryCombo.setParent(controlsParent);
                header.moveAbove(horizontal ? messageField : messageSplit);
                repositoryCombo.moveBelow(header);
                primaryRow.setParent(horizontal ? messageArea : lower);
                if (horizontal)
                    primaryRow.moveBelow(messageField);
                else
                    primaryRow.moveAbove(content);
                parent.layout(true, true);
                content.setWeights(horizontal ? new int[] {55, 45} : new int[] {70, 30});
                if (horizontal)
                {
                    messageSplit.setWeights(30, 70);
                    messageSplitInitialized = true;
                }
                else
                    messageSplitInitialized = false;
            }
            if (!horizontal && !messageSplitInitialized)
            {
                int totalHeight = messageSplit.getClientArea().height - messageSplit.getSashWidth();
                if (totalHeight > messageData.heightHint)
                {
                    messageSplit.setWeights(messageData.heightHint, totalHeight - messageData.heightHint);
                    messageSplitInitialized = true;
                }
            }
        });
        Composite footer = new Composite(lower, SWT.NONE);
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
        TreeColumn discard = new TreeColumn(tree, SWT.CENTER);
        discard.setWidth(32);
        TreeColumn action = new TreeColumn(tree, SWT.CENTER);
        action.setWidth(32);
        tree.addListener(SWT.Resize, event ->
            file.setWidth(Math.max(100, tree.getClientArea().width
                - discard.getWidth() - action.getWidth() - 3)));
        tree.addListener(SWT.Expand, event -> rememberExpansion((TreeItem) event.item, true));
        tree.addListener(SWT.Collapse, event -> rememberExpansion((TreeItem) event.item, false));
        tree.addListener(SWT.MeasureItem, event ->
        {
            if (event.index != 0 || !(event.item instanceof TreeItem item)
                || !(item.getData() instanceof FileChange change))
                return;
            ChangeDisplay display = (ChangeDisplay) item.getData("display"); //$NON-NLS-1$
            Image icon = metadataImage(display.icon());
            int width = event.gc.textExtent(display.text()).x;
            if (icon != null)
                width += icon.getBounds().width + 3;
            event.width = width;
            if (icon != null)
                event.height = Math.max(event.height, icon.getBounds().height);
        });
        tree.addListener(SWT.PaintItem, event ->
        {
            if (event.index != 0 || !(event.item instanceof TreeItem item)
                || !(item.getData() instanceof FileChange change))
                return;
            ChangeDisplay display = (ChangeDisplay) item.getData("display"); //$NON-NLS-1$
            Image icon = metadataImage(display.icon());
            GC gc = event.gc;
            int x = event.x;
            if (icon != null)
            {
                gc.drawImage(icon, x, event.y + Math.max(0, (event.height - icon.getBounds().height) / 2));
                x += icon.getBounds().width + 3;
            }
            String text = display.text();
            String state = change.state();
            int stateWidth = gc.textExtent(state).x;
            int stateX = tree.getClientArea().x + file.getWidth() - stateWidth - 4;
            Rectangle oldClip = gc.getClipping();
            Rectangle textClip = oldClip.intersection(new Rectangle(x, event.y,
                Math.max(0, stateX - x - 4), event.height));
            gc.setClipping(textClip);
            gc.drawText(text, x, event.y + Math.max(0,
                (event.height - gc.textExtent(text).y) / 2), SWT.DRAW_TRANSPARENT);
            gc.setClipping(oldClip);
            var foreground = gc.getForeground();
            gc.setForeground(tree.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
            gc.drawText(state, stateX, event.y + Math.max(0,
                (event.height - gc.textExtent(state).y) / 2), SWT.DRAW_TRANSPARENT);
            gc.setForeground(foreground);
            event.doit = false;
        });
        tree.addListener(SWT.MouseMove, event ->
        {
            TreeItem item = tree.getItem(new Point(event.x, event.y));
            if (item == null)
                tree.setToolTipText(null);
            else if (canDeleteUntracked(item) && item.getBounds(1).contains(event.x, event.y))
                tree.setToolTipText(Messages.get("deleteNewFile")); //$NON-NLS-1$
            else if (item.getBounds(1).contains(event.x, event.y)
                && (canDiscard(item) || canDiscardGroup(item)))
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
            else if (item.getData() instanceof FileChange change)
            {
                ChangeDisplay display = (ChangeDisplay) item.getData("display"); //$NON-NLS-1$
                tree.setToolTipText(display.text() + "\n" + change.path()); //$NON-NLS-1$
            }
            else
                tree.setToolTipText(null);
        });
        tree.addListener(SWT.MouseDown, event ->
        {
            if (event.button != 1)
                return;
            TreeItem item = tree.getItem(new Point(event.x, event.y));
            if (item == null)
                return;
            if (item.getBounds(1).contains(event.x, event.y) && canDiscardGroup(item))
                discardUnstagedChanges();
            else if (item.getBounds(1).contains(event.x, event.y) && canDeleteUntracked(item))
                deleteUntrackedFile((FileChange) item.getData());
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
            else if (event.keyCode == SWT.DEL && canDeleteUntracked(selected[0]))
                deleteUntrackedFile((FileChange) selected[0].getData());
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
                && !(isChangeGroup(item) && (canDiscardGroup(item) || hasChanges()
                    || hasStashedChanges)))
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
                if (!staged && canDiscardGroup(false))
                {
                    menuItem(menu, Messages.get("discardAllChanges"), this::discardUnstagedChanges) //$NON-NLS-1$
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
                else if (hasStashedChanges)
                {
                    if (added)
                        new MenuItem(menu, SWT.SEPARATOR);
                    menuItem(menu, Messages.get("restoreLastStash"), this::restoreLastStash) //$NON-NLS-1$
                        .setEnabled(!busy);
                }
            }
            else if (item.getData() instanceof FileChange change)
            {
                boolean staged = Boolean.TRUE.equals(item.getParentItem().getData());
                menuItem(menu, Messages.get("openChanges"), () -> openDiff(change.path(), staged)); //$NON-NLS-1$
                MenuItem open = menuItem(menu, Messages.get("openFile"), () -> openFile(change.path())); //$NON-NLS-1$
                open.setEnabled(repository != null
                    && new File(repository.getWorkTree(), change.path()).isFile());
                String remote = repository == null ? null
                    : repository.getConfig().getString("remote", "origin", "url"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                String provider = GitLinks.providerName(remote);
                menuItem(menu, Messages.get("openFileOnProvider") + " " //$NON-NLS-1$
                    + (provider == null ? Messages.get("gitRepository") : provider), //$NON-NLS-1$
                    () -> OpenLinkHandler.open(repository, GitLinks.Target.FILE, change.path()));
                new MenuItem(menu, SWT.SEPARATOR);
                MenuItem history = menuItem(menu, Messages.get("fileHistory"), //$NON-NLS-1$
                    () -> openFileHistory(change.path()));
                history.setEnabled(workspaceFile(change.path()) != null
                    && !"U".equals(change.state()) && !"A".equals(change.state()) //$NON-NLS-1$ //$NON-NLS-2$
                    && changes.staged().stream().noneMatch(entry -> entry.path().equals(change.path())
                        && "A".equals(entry.state()))); //$NON-NLS-1$
                new MenuItem(menu, SWT.SEPARATOR);
                boolean added = false;
                if (!staged && canDiscard(item))
                {
                    menuItem(menu, Messages.get("discardChanges"), //$NON-NLS-1$
                        () -> discardFile(change)).setEnabled(!busy);
                    added = true;
                }
                else if (!staged && canDeleteUntracked(item))
                {
                    menuItem(menu, Messages.get("deleteNewFile"), //$NON-NLS-1$
                        () -> deleteUntrackedFile(change)).setEnabled(!busy);
                    added = true;
                }
                menuItem(menu, Messages.get(staged ? "unstageFile" : "stageFile"), //$NON-NLS-1$ //$NON-NLS-2$
                    () -> changeFile(change.path(), staged)).setEnabled(!busy);
                new MenuItem(menu, SWT.SEPARATOR);
                menuItem(menu, Messages.get("hideAllChanges"), this::hideChanges) //$NON-NLS-1$
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
        return item.getData() instanceof FileChange change
            && !Boolean.TRUE.equals(item.getParentItem().getData()) && canDiscard(change);
    }

    private boolean canDiscard(FileChange change)
    {
        return ("M".equals(change.state()) || "D".equals(change.state())) //$NON-NLS-1$ //$NON-NLS-2$
            && changes.staged().stream().noneMatch(file -> file.path().equals(change.path())
                && "A".equals(file.state())); //$NON-NLS-1$
    }

    private static boolean canDeleteUntracked(TreeItem item)
    {
        return item.getData() instanceof FileChange change
            && !Boolean.TRUE.equals(item.getParentItem().getData()) && "U".equals(change.state()); //$NON-NLS-1$
    }

    private static boolean isChangeGroup(TreeItem item)
    {
        return item.getData() instanceof Boolean;
    }

    private boolean hasChanges()
    {
        return !changes.staged().isEmpty() || !changes.unstaged().isEmpty();
    }

    private boolean canDiscardUnstagedChanges()
    {
        return canDiscardGroup(false);
    }

    private boolean canDiscardGroup(TreeItem item)
    {
        return item.getData() instanceof Boolean staged && !staged && canDiscardGroup(false);
    }

    private boolean canDiscardGroup(boolean staged)
    {
        List<FileChange> files = staged ? changes.staged() : changes.unstaged();
        for (FileChange change : files)
            if ("M".equals(change.state()) || "D".equals(change.state()) //$NON-NLS-1$ //$NON-NLS-2$
                || staged && "A".equals(change.state()) || !staged && "U".equals(change.state())) //$NON-NLS-1$ //$NON-NLS-2$
                return true;
        return false;
    }

    private int discardableUnstagedCount()
    {
        Set<String> paths = new HashSet<>();
        for (FileChange change : changes.unstaged())
            if ("M".equals(change.state()) || "D".equals(change.state()) || "U".equals(change.state())) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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

    private Menu historyMenu(RecentHistory.Entry entry)
    {
        Repository repository = selectedRepository();
        if (repository == null)
            return null;
        Menu menu = new Menu(historyPane.getShell(), SWT.POP_UP);
        historyItem(menu, "Открыть изменения", () -> openCommit(entry)); //$NON-NLS-1$
        String remote = repository.getConfig().getString("remote", "origin", "url"); //$NON-NLS-1$ //$NON-NLS-2$
        String provider = GitLinks.providerName(remote);
        if (remote != null && !remote.isBlank())
            historyItem(menu, provider == null ? "Открыть коммит в удалённом репозитории" //$NON-NLS-1$
                : "Открыть коммит в " + provider, //$NON-NLS-1$
                () -> openCommitLink(entry.hash(), remote));
        new MenuItem(menu, SWT.SEPARATOR);
        if (historyHead != null && entry.hash().equals(historyHead.name()))
        {
            historyItem(menu, Messages.get("mergeBranch"), () -> mergeBranch(repository)) //$NON-NLS-1$
                .setEnabled(!isRunning(repository));
        }
        historyItem(menu, "Переключиться на коммит (без ветки)", //$NON-NLS-1$
            () -> BranchOperationUI.checkout(repository, entry.hash(), true).start());
        historyItem(menu, "Создать ветку…", //$NON-NLS-1$
            () -> createBranchAt(repository, entry.hash()));
        historyItem(menu, "Создать метку…", () -> createTagAt(repository, entry.hash())); //$NON-NLS-1$
        historyItem(menu, "Скопировать коммит (cherry-pick)", () -> cherryPick(repository, entry)); //$NON-NLS-1$
        new MenuItem(menu, SWT.SEPARATOR);
        String upstream = upstream(repository);
        MenuItem remoteCompare = historyItem(menu, "Сравнить с удалённой веткой", //$NON-NLS-1$
            () -> compareWith(repository, entry.hash(), upstream));
        remoteCompare.setEnabled(upstream != null && resolves(repository, upstream));
        MenuItem mergeBaseCompare = historyItem(menu, "Сравнить с точкой слияния", //$NON-NLS-1$
            () -> compareWithMergeBase(repository, entry.hash()));
        mergeBaseCompare.setEnabled(upstream != null && resolves(repository, upstream));
        historyItem(menu, "Сравнить с…", () -> compareWithSelectedRef(repository, entry.hash())); //$NON-NLS-1$
        MenuItem resetItem = new MenuItem(menu, SWT.CASCADE);
        resetItem.setText("Сброс"); //$NON-NLS-1$
        Menu resetMenu = new Menu(menu);
        resetItem.setMenu(resetMenu);
        historyItem(resetMenu, "Мягкий (сохранить подготовленные изменения)", //$NON-NLS-1$
            () -> ResetMenu.performReset(getSite().getShell(), repository, entry.plot().getId(), ResetType.SOFT));
        historyItem(resetMenu, "Смешанный (вернуть всё в изменения)", //$NON-NLS-1$
            () -> ResetMenu.performReset(getSite().getShell(), repository, entry.plot().getId(), ResetType.MIXED));
        historyItem(resetMenu, "Жёсткий (удалить все изменения)", //$NON-NLS-1$
            () -> ResetMenu.performReset(getSite().getShell(), repository, entry.plot().getId(), ResetType.HARD));
        new MenuItem(menu, SWT.SEPARATOR);
        historyItem(menu, "Копировать хеш коммита", () -> copyText(entry.hash())); //$NON-NLS-1$
        historyItem(menu, "Копировать сообщение коммита", () -> copyText(entry.message())); //$NON-NLS-1$
        return menu;
    }

    private static MenuItem historyItem(Menu menu, String label, Runnable action)
    {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(label);
        item.addListener(SWT.Selection, event -> action.run());
        return item;
    }

    private boolean syncingStagingMessage;

    static void stagingMessageAvailable()
    {
        if (instance != null)
            instance.syncStagingMessage(false);
    }

    private void syncStagingMessage(boolean publish)
    {
        if (syncingStagingMessage || messageField == null || messageField.isDisposed())
            return;
        var view = getSite().getPage().findView("org.eclipse.egit.ui.StagingView"); //$NON-NLS-1$
        if (view == null)
            return;
        try
        {
            Repository repository = (Repository) view.getClass().getMethod("getCurrentRepository").invoke(view); //$NON-NLS-1$
            if (!sameRepository(selectedRepository(), repository))
                return;
            java.lang.reflect.Field field = null;
            for (Class<?> type = view.getClass(); type != null && field == null; type = type.getSuperclass())
                try
                {
                    field = type.getDeclaredField("commitMessageText"); //$NON-NLS-1$
                }
                catch (NoSuchFieldException e)
                {
                    // EDT may declare the control in the EGit base class.
                }
            if (field == null)
                return;
            field.setAccessible(true);
            Object area = field.get(view);
            if (area == null)
                return;
            var text = (org.eclipse.swt.custom.StyledText) area.getClass().getMethod("getTextWidget").invoke(area); //$NON-NLS-1$
            if (text.isDisposed())
                return;
            if (text.getData("gitflow.messageSync") != this) //$NON-NLS-1$
            {
                text.setData("gitflow.messageSync", this); //$NON-NLS-1$
                text.addModifyListener(event -> syncStagingMessage(false));
            }
            syncingStagingMessage = true;
            if (publish)
            {
                if (!text.getText().equals(messageField.getText()))
                    view.getClass().getMethod("setCommitText", String.class).invoke(view, messageField.getText()); //$NON-NLS-1$
            }
            else if (!messageField.getText().equals(text.getText()))
                messageField.setText(text.getText());
        }
        catch (ReflectiveOperationException e)
        {
            org.eclipse.core.runtime.Platform.getLog(getClass()).warn("Не удалось синхронизировать сообщение коммита с Git Staging.", e); //$NON-NLS-1$
        }
        finally
        {
            syncingStagingMessage = false;
        }
    }

    private void mergeBranch(Repository repository)
    {
        IProject project = null;
        for (IProject candidate : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            if (candidate.isOpen() && repository.equals(RepositorySupport.resolveFor(candidate)))
            {
                project = candidate;
                break;
            }
        if (project == null)
        {
            showFeedback(Messages.get("mergeProjectMissing")); //$NON-NLS-1$
            return;
        }
        try
        {
            IHandlerService handlers = getSite().getService(IHandlerService.class);
            ICommandService commands = getSite().getService(ICommandService.class);
            StructuredSelection selection = new StructuredSelection(project);
            IEvaluationContext context = new EvaluationContext(handlers.createContextSnapshot(false),
                selection.toList());
            context.addVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME, selection);
            context.addVariable(ISources.ACTIVE_MENU_SELECTION_NAME, selection);
            handlers.executeCommandInContext(org.eclipse.core.commands.ParameterizedCommand.generateCommand(
                commands.getCommand("org.eclipse.egit.ui.team.Merge"), Map.of()), null, context); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            showFeedback(Messages.get("mergeOpenFailed") + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void openCommit(RecentHistory.Entry entry)
    {
        Repository repository = selectedRepository();
        if (repository == null)
            return;
        try
        {
            CommitEditor.open(new RepositoryCommit(repository, repository.parseCommit(entry.plot())));
        }
        catch (Exception e)
        {
            showFeedback("Не удалось открыть коммит: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private void openCommitLink(String hash, String remote)
    {
        try
        {
            String url = GitLinks.fromRemote(remote, GitLinks.Target.COMMIT, hash, null);
            if (!Program.launch(url))
                copyText(url);
        }
        catch (RuntimeException e)
        {
            showFeedback(e.getMessage());
        }
    }

    private void createTagAt(Repository repository, String hash)
    {
        try
        {
            ObjectId commitId = ObjectId.fromString(hash);
            CreateTagDialog dialog = new CreateTagDialog(getSite().getShell(), commitId, repository);
            if (dialog.open() != org.eclipse.jface.window.Window.OK)
                return;
            RevCommit target = repository.parseCommit(commitId);
            String name = dialog.getTagName();
            boolean pushAfter = dialog.shouldStartPushWizard();
            TagOperation operation = new TagOperation(repository).setName(name).setTarget(target)
                .setAnnotated(dialog.isAnnotated()).setForce(dialog.shouldOverWriteTag())
                .setSign(dialog.shouldSign()).setMessage(dialog.getTagMessage())
                .setCredentialsProvider(new EGitCredentialsProvider());
            Job job = new Job("Создание метки " + name) //$NON-NLS-1$
            {
                @Override
                protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor)
                {
                    try
                    {
                        operation.execute(monitor);
                        return Status.OK_STATUS;
                    }
                    catch (CoreException e)
                    {
                        return e.getStatus();
                    }
                }
            };
            job.addJobChangeListener(new JobChangeAdapter()
            {
                @Override
                public void done(IJobChangeEvent event)
                {
                    onUi(() ->
                    {
                        if (event.getResult().isOK())
                        {
                            showFeedback("Метка создана: " + name); //$NON-NLS-1$
                            if (pushAfter)
                                PushTagsWizard.openWizardDialog(repository, name);
                        }
                        else
                            showFeedback("Не удалось создать метку: " + event.getResult().getMessage()); //$NON-NLS-1$
                    });
                }
            });
            job.setUser(true);
            job.setRule(operation.getSchedulingRule());
            job.schedule();
        }
        catch (Exception e)
        {
            showFeedback("Не удалось открыть создание метки: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private void createBranchAt(Repository repository, String hash)
    {
        try
        {
            new WizardDialog(getSite().getShell(), new CreateBranchWizard(repository, hash)).open();
        }
        catch (RuntimeException e)
        {
            showFeedback("Не удалось открыть создание ветки: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private void cherryPick(Repository repository, RecentHistory.Entry entry)
    {
        try
        {
            new CherryPickUI().run(repository, repository.parseCommit(entry.plot()), true);
        }
        catch (Exception e)
        {
            showFeedback("Не удалось скопировать коммит: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private void compareWith(Repository repository, String selected, String other)
    {
        if (other != null)
            compareCommits(repository, other, selected);
    }

    private void compareWithMergeBase(Repository repository, String selected)
    {
        try (RevWalk walk = new RevWalk(repository))
        {
            RevCommit current = walk.parseCommit(repository.resolve(Constants.HEAD));
            RevCommit selectedCommit = walk.parseCommit(repository.resolve(selected));
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(current);
            walk.markStart(selectedCommit);
            RevCommit base = walk.next();
            if (base == null)
                showFeedback("Общая точка слияния не найдена."); //$NON-NLS-1$
            else
                compareCommits(repository, base.name(), selected);
        }
        catch (Exception e)
        {
            showFeedback("Не удалось найти точку слияния: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private void compareWithSelectedRef(Repository repository, String selected)
    {
        String ref = SmartCheckoutHandler.selectReference(getSite().getShell(), repository);
        if (ref != null)
            compareCommits(repository, selected, ref);
    }

    private void compareCommits(Repository repository, String oldRevision, String newRevision)
    {
        try
        {
            CompareTreeView view = (CompareTreeView) getSite().getPage().showView(CompareTreeView.ID);
            view.setInput(repository, oldRevision, newRevision);
        }
        catch (Exception e)
        {
            showFeedback("Не удалось сравнить коммиты: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static String upstream(Repository repository)
    {
        try
        {
            String branch = repository.getBranch();
            String tracking = new BranchConfig(repository.getConfig(), branch).getRemoteTrackingBranch();
            if (tracking != null && repository.resolve(tracking) != null)
                return tracking;
            String origin = Constants.R_REMOTES + "origin/" + branch; //$NON-NLS-1$
            return repository.resolve(origin) == null ? null : origin;
        }
        catch (IOException e)
        {
            return null;
        }
    }

    private static boolean resolves(Repository repository, String revision)
    {
        try
        {
            return repository.resolve(revision) != null;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    private static void copyText(String value)
    {
        Clipboard clipboard = new Clipboard(Display.getDefault());
        try
        {
            clipboard.setContents(new Object[] { value }, new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            clipboard.dispose();
        }
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
            overviewHead = null;
            overviewUpstream = null;
            changes = new WorkingChanges(List.of(), List.of());
            hasStashedChanges = false;
            historyHead = null;
            historyEntries.clear();
            historyHasMore = false;
            historyLoading = false;
            historyStateKey = null;
            historyPane.setCommits(List.of(), false, null);
            movedPath = null;
            fillChangesTree();
        }
        observeIndexChanges(repository);
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
        updatePrimary();
        scheduleRefresh();
    }

    private void observeIndexChanges(Repository repository)
    {
        if (sameRepository(indexObservedRepository, repository))
            return;
        if (indexDiffEntry != null)
        {
            indexDiffEntry.removeIndexDiffChangedListener(indexDiffListener);
            indexDiffEntry = null;
        }
        indexObservedRepository = repository;
        indexDiffData = null;
        if (repository != null)
        {
            indexDiffEntry = IndexDiffCache.INSTANCE.getIndexDiffCacheEntry(repository);
            indexDiffEntry.addIndexDiffChangedListener(indexDiffListener);
            indexDiffData = indexDiffEntry.getIndexDiff();
            if (indexDiffData != null)
                applyIndexDiff(repository, indexDiffData);
        }
    }

    private static boolean sameRepository(Repository first, Repository second)
    {
        return first == second || first != null && second != null
            && first.getDirectory().equals(second.getDirectory());
    }

    private void indexDiffChanged(Repository repository, IndexDiffData data)
    {
        if (!sameRepository(indexObservedRepository, repository))
            return;
        indexDiffData = data;
        onUi(() ->
        {
            if (instance == GitFlowView.this && sameRepository(selectedRepository(), repository))
                applyIndexDiff(repository, data);
        });
    }

    private void applyIndexDiff(Repository repository, IndexDiffData data)
    {
        if (!sameRepository(selectedRepository(), repository))
            return;
        WorkingChanges latestChanges = changesFrom(data);
        if (!changes.equals(latestChanges))
        {
            changes = latestChanges;
            int changedFiles = (int) java.util.stream.Stream.concat(changes.staged().stream(), changes.unstaged().stream())
                .map(FileChange::path).distinct().count();
            overview = new RepositoryOverview(changedFiles, overview.incoming(), overview.outgoing());
            fillChangesTree();
            updatePrimary();
        }
        scheduleRefresh(0);
    }

    private static WorkingChanges changesFrom(IndexDiffData data)
    {
        List<FileChange> staged = new ArrayList<>();
        List<FileChange> unstaged = new ArrayList<>();
        addChanges(staged, data.getAdded(), "A"); //$NON-NLS-1$
        addChanges(staged, data.getChanged(), "M"); //$NON-NLS-1$
        addChanges(staged, data.getRemoved(), "D"); //$NON-NLS-1$
        addChanges(unstaged, data.getModified(), "M"); //$NON-NLS-1$
        addChanges(unstaged, data.getMissing(), "D"); //$NON-NLS-1$
        addChanges(unstaged, data.getUntracked(), "U"); //$NON-NLS-1$
        addChanges(unstaged, data.getConflicting(), "C"); //$NON-NLS-1$
        staged.sort(Comparator.comparing(FileChange::path));
        unstaged.sort(Comparator.comparing(FileChange::path));
        return new WorkingChanges(List.copyOf(staged), List.copyOf(unstaged));
    }

    private static void addChanges(List<FileChange> changes, Iterable<String> paths, String state)
    {
        for (String path : paths)
            changes.add(new FileChange(path, state));
    }

    private static final long REFRESH_DELAY = 300;
    private static final long REFRESH_QUICK_DELAY = 100;

    private void scheduleRefresh()
    {
        scheduleRefresh(REFRESH_DELAY);
    }

    private void scheduleRefresh(long delay)
    {
        if (refreshJob != null)
        {
            if (refreshJob.getState() == Job.RUNNING)
            {
                refreshAgain = true;
                generation++;
                return;
            }
            refreshJob.cancel();
        }
        int current = ++generation;
        Repository repository = selectedRepository();
        if (repository == null || RUNNING.containsKey(repository.getDirectory()))
            return;
        ObjectId displayedHead = historyHead;
        String displayedHistoryState = historyStateKey;
        ObjectId displayedOverviewHead = overviewHead;
        ObjectId displayedOverviewUpstream = overviewUpstream;
        RepositoryOverview displayedOverview = overview;
        IndexDiffData displayedIndexDiff = sameRepository(indexObservedRepository, repository) ? indexDiffData : null;
        refreshJob = new Job(Messages.get("statusReading")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor)
            {
                try
                {
                    WorkingChanges latestChanges = displayedIndexDiff == null ? WorkingChanges.read(repository)
                        : changesFrom(displayedIndexDiff);
                    boolean latestHasStashedChanges = StashOperations.hasStash(repository);
                    RepositoryOverview latestOverview = RepositoryOverview.read(repository, latestChanges,
                        displayedOverview, displayedOverviewHead, displayedOverviewUpstream);
                    RepositoryState latestState = repository.getRepositoryState();
                    String mergeMessage = latestState == RepositoryState.MERGING_RESOLVED
                        ? repository.readMergeCommitMsg() : null;
                    ObjectId latestHead = repository.resolve(Constants.HEAD);
                    String latestHistoryState = RecentHistory.stateKey(repository);
                    String tracking = new BranchConfig(repository.getConfig(), repository.getBranch())
                        .getRemoteTrackingBranch();
                    ObjectId latestUpstream = tracking == null ? null : repository.resolve(tracking);
                    List<RecentHistory.Entry> latestHistory = Objects.equals(latestHistoryState,
                        displayedHistoryState) ? null : RecentHistory.read(repository, 0, HISTORY_PAGE_SIZE + 1);
                    onUi(() ->
                    {
                        if (instance == GitFlowView.this && current == generation)
                        {
                            boolean changesUpdated = !changes.equals(latestChanges);
                            changes = latestChanges;
                            hasStashedChanges = latestHasStashedChanges;
                            overview = latestOverview;
                            overviewHead = latestHead;
                            overviewUpstream = latestUpstream;
                            updateMergeMessage(latestState, mergeMessage);
                            syncStagingMessage(false);
                            if (latestHistory != null)
                            {
                                historyHead = latestHead;
                                historyStateKey = latestHistoryState;
                                historyEntries.clear();
                                historyEntries.addAll(latestHistory.subList(0,
                                    Math.min(HISTORY_PAGE_SIZE, latestHistory.size())));
                                historyHasMore = latestHistory.size() > HISTORY_PAGE_SIZE;
                                historyLoading = false;
                                historyPane.setCommits(historyEntries, historyHasMore,
                                    GitFlowView.this::loadMoreHistory);
                            }
                            if (changesUpdated)
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
        Job scheduledRefresh = refreshJob;
        scheduledRefresh.addJobChangeListener(new JobChangeAdapter()
        {
            @Override
            public void done(IJobChangeEvent event)
            {
                onUi(() ->
                {
                    if (refreshJob == scheduledRefresh)
                        refreshJob = null;
                    if (instance == GitFlowView.this && refreshAgain)
                    {
                        refreshAgain = false;
                        scheduleRefresh(REFRESH_QUICK_DELAY);
                    }
                });
            }
        });
        refreshJob.setSystem(true);
        refreshJob.schedule(delay);
    }

    private void loadMoreHistory()
    {
        Repository repository = selectedRepository();
        ObjectId expectedHead = historyHead;
        String expectedHistoryState = historyStateKey;
        if (repository == null || expectedHead == null || historyLoading || !historyHasMore)
            return;
        historyLoading = true;
        historyPane.setLoadingMore(true);
        int offset = historyEntries.size();
        new Job("Загрузить следующие коммиты") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor)
            {
                try
                {
                    List<RecentHistory.Entry> page = RecentHistory.read(repository, offset,
                        HISTORY_PAGE_SIZE + 1);
                    ObjectId currentHead = repository.resolve(Constants.HEAD);
                    String currentHistoryState = RecentHistory.stateKey(repository);
                    onUi(() ->
                    {
                        if (!sameRepository(selectedRepository(), repository))
                            return;
                        historyLoading = false;
                        if (!Objects.equals(historyHead, expectedHead)
                            || !Objects.equals(currentHead, expectedHead)
                            || !Objects.equals(historyStateKey, expectedHistoryState)
                            || !Objects.equals(currentHistoryState, expectedHistoryState))
                        {
                            historyPane.setLoadingMore(false);
                            scheduleRefresh(0);
                            return;
                        }
                        int loaded = Math.min(HISTORY_PAGE_SIZE, page.size());
                        List<RecentHistory.Entry> nextPage = page.subList(0, loaded);
                        historyEntries.addAll(nextPage);
                        historyHasMore = page.size() > HISTORY_PAGE_SIZE;
                        historyPane.appendCommits(nextPage, historyHasMore,
                            GitFlowView.this::loadMoreHistory);
                    });
                    return Status.OK_STATUS;
                }
                catch (Exception e)
                {
                    onUi(() ->
                    {
                        if (sameRepository(selectedRepository(), repository))
                        {
                            historyLoading = false;
                            historyPane.setLoadingMore(false);
                            showFeedback("Не удалось загрузить коммиты: " + e.getMessage()); //$NON-NLS-1$
                        }
                    });
                    return new Status(IStatus.ERROR, PLUGIN_ID, e.getMessage(), e);
                }
            }
        }.schedule();
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
            !staged && canDiscardGroup(false) ? "↶" : "", //$NON-NLS-1$
            files.isEmpty() ? "" : staged ? "−" : "+"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        group.setData(staged);
        for (FileChange change : files)
        {
            TreeItem item = new TreeItem(group, SWT.NONE);
            ChangeDisplay display = displayChangePath(change.path());
            // The first column is owner-drawn below; leaving its native text populated
            // makes SWT paint the same label a second time on some platforms.
            item.setText(new String[] {"",
                !staged && "U".equals(change.state()) ? "×" //$NON-NLS-1$
                    : !staged && canDiscard(change) ? "↶" : "", staged ? "−" : "+"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            item.setData("display", display); //$NON-NLS-1$
            item.setData(change);
            if (staged == selectedStaged && change.path().equals(selectedPath))
                moved = item;
        }
        group.setExpanded(expanded);
        return moved;
    }

    private record ChangeDisplay(String text, String icon)
    {
    }

    private record PendingIndexChange(Repository repository, String path, boolean staged)
    {
    }

    private ChangeDisplay displayChangePath(String path)
    {
        String relative = path.replace('\\', '/'); //$NON-NLS-1$
        if (relative.startsWith("bp3/src/")) //$NON-NLS-1$
            relative = relative.substring("bp3/src/".length()); //$NON-NLS-1$
        else if (relative.startsWith("src/")) //$NON-NLS-1$
            relative = relative.substring("src/".length()); //$NON-NLS-1$

        if ("Configuration.xml".equals(relative)) //$NON-NLS-1$
            return namedChange("Configuration", "metadataConfiguration", "configuration"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String[] parts = relative.split("/"); //$NON-NLS-1$
        if (parts.length < 2)
            return new ChangeDisplay(relative, null);

        String objectType = metadataTypeKey(parts[0]);
        if (objectType == null)
            return new ChangeDisplay(relative, null);

        String objectName = parts[1];
        String fileName = parts[parts.length - 1];
        String icon = metadataIconName(parts[0]);
        if ("CommonModules".equals(parts[0]) && parts.length >= 3) //$NON-NLS-1$
            return componentChange(objectName, "partModule", objectType, icon); //$NON-NLS-1$
        if ("Roles".equals(parts[0]) && "Rights.xml".equals(fileName) && parts.length >= 3) //$NON-NLS-1$ //$NON-NLS-2$
            return namedChange(objectName, objectType, icon);

        int forms = -1;
        for (int i = 2; i < parts.length; i++)
            if ("Forms".equals(parts[i])) //$NON-NLS-1$
            {
                forms = i;
                break;
            }
        boolean formChange = forms >= 0 && forms + 1 < parts.length;
        if (formChange)
        {
            String formIcon = metadataImage(icon) == null ? null : icon;
            String formType = "metadataForm" + objectType.substring("metadata".length()); //$NON-NLS-1$
            String formPart = "Module.bsl".equals(fileName) ? "partFormModule" : "partForm"; //$NON-NLS-1$ //$NON-NLS-2$
            String label = objectName + " " + stripExtension(parts[forms + 1]) //$NON-NLS-1$
                + " " + Messages.get(formPart); //$NON-NLS-1$
            if (formIcon == null)
                label += " " + Messages.getOrDefault(formType, Messages.get("metadataForm")); //$NON-NLS-1$
            return new ChangeDisplay(label, formIcon);
        }
        if ("ManagerModule.bsl".equals(fileName)) //$NON-NLS-1$
            return componentChange(objectName, "partManagerModule", objectType, icon); //$NON-NLS-1$
        if ("ObjectModule.bsl".equals(fileName)) //$NON-NLS-1$
            return componentChange(objectName, "partObjectModule", objectType, icon); //$NON-NLS-1$
        if ("RecordSetModule.bsl".equals(fileName)) //$NON-NLS-1$
            return componentChange(objectName, "partRecordSetModule", objectType, icon); //$NON-NLS-1$
        if ("ValueManagerModule.bsl".equals(fileName)) //$NON-NLS-1$
            return componentChange(objectName, "partValueManagerModule", objectType, icon); //$NON-NLS-1$
        if ("Module.bsl".equals(fileName) //$NON-NLS-1$
            && ("metadataCommonModule".equals(objectType) || "metadataCommonForm".equals(objectType) //$NON-NLS-1$ //$NON-NLS-2$
                || "metadataHttpService".equals(objectType) || "metadataWebService".equals(objectType)) //$NON-NLS-1$ //$NON-NLS-2$
            )
            return componentChange(objectName, "partModule", objectType, icon); //$NON-NLS-1$
        if ("metadataCommonForm".equals(objectType) && "Form.xml".equals(fileName) //$NON-NLS-1$ //$NON-NLS-2$
            && containsPath(parts, "Ext", "Form.xml")) //$NON-NLS-1$ //$NON-NLS-2$
            return namedChange(objectName, objectType, icon);
        if (parts.length == 2 && fileName.endsWith(".xml")) //$NON-NLS-1$
            return namedChange(stripExtension(objectName), objectType, icon);
        // Files nested under a metadata object (templates, commands, layouts, etc.)
        // still belong to that object. Showing the repository path exposes folders
        // such as Catalogs and makes otherwise recognized metadata look inconsistent.
        return namedChange(objectName, objectType, icon);
    }

    private ChangeDisplay namedChange(String name, String typeKey, String icon)
    {
        String usableIcon = metadataImage(icon) == null ? null : icon;
        return new ChangeDisplay(usableIcon == null ? name + " " + Messages.get(typeKey) : name, usableIcon);
    }

    private ChangeDisplay componentChange(String name, String componentKey, String typeKey, String icon)
    {
        String usableIcon = metadataImage(icon) == null ? null : icon;
        String label = componentKey.isEmpty() ? name : name + " " + Messages.get(componentKey); //$NON-NLS-1$
        if (usableIcon == null)
            label += " " + Messages.get(typeKey); //$NON-NLS-1$
        return new ChangeDisplay(label, usableIcon);
    }

    private static String stripExtension(String name)
    {
        int extension = name.lastIndexOf('.'); //$NON-NLS-1$
        return extension < 0 ? name : name.substring(0, extension);
    }

    private Image metadataImage(String name)
    {
        if (name == null)
            return null;
        Image cached = metadataImages.get(name);
        if (cached != null && !cached.isDisposed())
            return cached;
        var bundle = Platform.getBundle("com._1c.g5.v8.dt.md.ui.shared"); //$NON-NLS-1$
        URL entry = bundle == null ? null : bundle.getEntry("icons/obj16/" + name + ".png"); //$NON-NLS-1$
        if (entry == null)
            return null;
        Image image = ImageDescriptor.createFromURL(entry).createImage();
        metadataImages.put(name, image);
        return image;
    }

    private static boolean containsPath(String[] parts, String... expected)
    {
        if (parts.length < expected.length)
            return false;
        for (int start = 0; start <= parts.length - expected.length; start++)
        {
            boolean match = true;
            for (int i = 0; i < expected.length; i++)
                if (!expected[i].equals(parts[start + i]))
                {
                    match = false;
                    break;
                }
            if (match)
                return true;
        }
        return false;
    }

    private static String metadataTypeKey(String folder)
    {
        return switch (folder)
        {
            case "AccountingRegisters" -> "metadataAccountingRegister"; //$NON-NLS-1$ //$NON-NLS-2$
            case "AccumulationRegisters" -> "metadataAccumulationRegister"; //$NON-NLS-1$ //$NON-NLS-2$
            case "BusinessProcesses" -> "metadataBusinessProcess"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Bots" -> "metadataBot"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Catalogs" -> "metadataCatalog"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ChartsOfAccounts" -> "metadataChartOfAccounts"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ChartsOfCalculationTypes" -> "metadataChartOfCalculationTypes"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ChartsOfCharacteristicTypes" -> "metadataChartOfCharacteristicTypes"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CommandGroups" -> "metadataCommandGroup"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CommonAttributes" -> "metadataCommonAttribute"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CommonCommands" -> "metadataCommonCommand"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CommonForms" -> "metadataCommonForm"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CommonModules" -> "metadataCommonModule"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CommonPictures" -> "metadataCommonPicture"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CommonTemplates" -> "metadataCommonTemplate"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Constants" -> "metadataConstant"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Configuration" -> "metadataConfiguration"; //$NON-NLS-1$ //$NON-NLS-2$
            case "DataProcessors" -> "metadataDataProcessor"; //$NON-NLS-1$ //$NON-NLS-2$
            case "DefinedTypes" -> "metadataDefinedType"; //$NON-NLS-1$ //$NON-NLS-2$
            case "DocumentJournals" -> "metadataDocumentJournal"; //$NON-NLS-1$ //$NON-NLS-2$
            case "DocumentNumerators" -> "metadataDocumentNumerator"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Documents" -> "metadataDocument"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Enums" -> "metadataEnum"; //$NON-NLS-1$ //$NON-NLS-2$
            case "EventSubscriptions" -> "metadataEventSubscription"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ExchangePlans" -> "metadataExchangePlan"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ExternalDataSources" -> "metadataExternalDataSource"; //$NON-NLS-1$ //$NON-NLS-2$
            case "FilterCriteria" -> "metadataFilterCriterion"; //$NON-NLS-1$ //$NON-NLS-2$
            case "FunctionalOptions" -> "metadataFunctionalOption"; //$NON-NLS-1$ //$NON-NLS-2$
            case "FunctionalOptionsParameters" -> "metadataFunctionalOptionParameter"; //$NON-NLS-1$ //$NON-NLS-2$
            case "HTTPServices" -> "metadataHttpService"; //$NON-NLS-1$ //$NON-NLS-2$
            case "InformationRegisters" -> "metadataInformationRegister"; //$NON-NLS-1$ //$NON-NLS-2$
            case "IntegrationServices" -> "metadataIntegrationService"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Languages" -> "metadataLanguage"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Roles" -> "metadataRole"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ScheduledJobs" -> "metadataScheduledJob"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Sequences" -> "metadataSequence"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Servers" -> "metadataServer"; //$NON-NLS-1$ //$NON-NLS-2$
            case "SessionParameters" -> "metadataSessionParameter"; //$NON-NLS-1$ //$NON-NLS-2$
            case "SettingsStorages" -> "metadataSettingsStorage"; //$NON-NLS-1$ //$NON-NLS-2$
            case "StyleItems" -> "metadataStyleItem"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Styles" -> "metadataStyle"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Subsystems" -> "metadataSubsystem"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Tasks" -> "metadataTask"; //$NON-NLS-1$ //$NON-NLS-2$
            case "PaletteColors" -> "metadataPaletteColor"; //$NON-NLS-1$ //$NON-NLS-2$
            case "XDTOPackages" -> "metadataXdtoPackage"; //$NON-NLS-1$ //$NON-NLS-2$
            case "WebServices" -> "metadataWebService"; //$NON-NLS-1$ //$NON-NLS-2$
            case "WebSocketClients" -> "metadataWebSocketClient"; //$NON-NLS-1$ //$NON-NLS-2$
            case "WSReferences" -> "metadataWsReference"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Reports" -> "metadataReport"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CalculationRegisters" -> "metadataCalculationRegister"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> null;
        };
    }

    private static String metadataIconName(String folder)
    {
        return switch (folder)
        {
            case "AccountingRegisters" -> "accounting_register"; //$NON-NLS-1$ //$NON-NLS-2$
            case "AccumulationRegisters" -> "accumulation_register"; //$NON-NLS-1$ //$NON-NLS-2$
            case "BusinessProcesses" -> "business_process"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Bots" -> "bot"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Catalogs" -> "catalog"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ChartsOfAccounts" -> "chart_of_accounts"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ChartsOfCalculationTypes" -> "chart_of_calculation_types"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ChartsOfCharacteristicTypes" -> "chart_of_characteristic_types"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CommandGroups" -> "command_group"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CommonAttributes" -> "common_attribute"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CommonCommands" -> "common_command"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CommonForms" -> "common_form"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CommonModules" -> "common_module"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CommonPictures" -> "common_picture"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CommonTemplates" -> "common_template"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Configuration" -> "configuration"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Constants" -> "constant"; //$NON-NLS-1$ //$NON-NLS-2$
            case "DataProcessors" -> "data_processor"; //$NON-NLS-1$ //$NON-NLS-2$
            case "DefinedTypes" -> "defined_type"; //$NON-NLS-1$ //$NON-NLS-2$
            case "DocumentJournals" -> "document_journal"; //$NON-NLS-1$ //$NON-NLS-2$
            case "DocumentNumerators" -> "document_numerator"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Documents" -> "document"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Enums" -> "enum"; //$NON-NLS-1$ //$NON-NLS-2$
            case "EventSubscriptions" -> "event_subscription"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ExchangePlans" -> "exchange_plan"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ExternalDataSources" -> "external_data_source"; //$NON-NLS-1$ //$NON-NLS-2$
            case "FilterCriteria" -> "filter_criterion"; //$NON-NLS-1$ //$NON-NLS-2$
            case "FunctionalOptions" -> "functional_option"; //$NON-NLS-1$ //$NON-NLS-2$
            case "FunctionalOptionsParameters" -> "functional_option_parameter"; //$NON-NLS-1$ //$NON-NLS-2$
            case "HTTPServices" -> "http"; //$NON-NLS-1$ //$NON-NLS-2$
            case "InformationRegisters" -> "information_register"; //$NON-NLS-1$ //$NON-NLS-2$
            case "IntegrationServices" -> "integration_service"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Languages" -> "language"; //$NON-NLS-1$ //$NON-NLS-2$
            case "PaletteColors" -> "palette_color"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Reports" -> "report"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Roles" -> "role"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ScheduledJobs" -> "scheduled_job"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Sequences" -> "sequence"; //$NON-NLS-1$ //$NON-NLS-2$
            case "SessionParameters" -> "session_parameter"; //$NON-NLS-1$ //$NON-NLS-2$
            case "SettingsStorages" -> "settings_storage"; //$NON-NLS-1$ //$NON-NLS-2$
            case "StyleItems" -> "style_item"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Styles" -> "style"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Subsystems" -> "subsystem"; //$NON-NLS-1$ //$NON-NLS-2$
            case "Tasks" -> "task"; //$NON-NLS-1$ //$NON-NLS-2$
            case "XDTOPackages" -> "xdto_package"; //$NON-NLS-1$ //$NON-NLS-2$
            case "WebServices" -> "web_service"; //$NON-NLS-1$ //$NON-NLS-2$
            case "WebSocketClients" -> "web_socket_client"; //$NON-NLS-1$ //$NON-NLS-2$
            case "WSReferences" -> "ws_reference"; //$NON-NLS-1$ //$NON-NLS-2$
            case "CalculationRegisters" -> "calculation_register"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> null;
        };
    }

    private void updatePrimary()
    {
        Repository repository = selectedRepository();
        boolean busy = repository == null || RUNNING.containsKey(repository.getDirectory());
        boolean hasChanges = !changes.staged().isEmpty() || !changes.unstaged().isEmpty();
        boolean mergeReady = repositoryState == RepositoryState.MERGING_RESOLVED;
        boolean hasRemoteChanges = overview.incoming() > 0 || overview.outgoing() > 0;
        primaryButton.setToolTipText(null);
        primaryButton.setText(sendAfterCommit && hasRemote ? Messages.get("commitAndPush") //$NON-NLS-1$
            : Messages.get("commitOnly")); //$NON-NLS-1$
        if (hasChanges || mergeReady)
        {
            boolean tracked = changes.unstaged().stream().anyMatch(change -> !"U".equals(change.state())); //$NON-NLS-1$
            primaryButton.setEnabled(!busy && !messageField.getText().isBlank()
                && (mergeReady || repositoryState == RepositoryState.SAFE
                    && (!changes.staged().isEmpty() || tracked)));
        }
        else
        {
            if (hasRemoteChanges)
            {
                primaryButton.setText(Messages.get("syncChanges") + " " + syncCountsText()); //$NON-NLS-1$
                primaryButton.setToolTipText(syncTooltip());
                primaryButton.setEnabled(repository != null && hasRemote && !busy
                    && repositoryState == RepositoryState.SAFE);
            }
            else
                primaryButton.setEnabled(false);
        }
        syncButton.setText(syncButtonText());
        syncButton.setToolTipText(syncTooltip());
        boolean canSync = repository != null && hasRemote && !busy
            && repositoryState == RepositoryState.SAFE;
        syncButton.setEnabled(canSync);
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

    private String syncCountsText()
    {
        return overview.incoming() < 0 ? "" //$NON-NLS-1$
            : overview.incoming() + "↓ " + overview.outgoing() + "↑"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String syncButtonText()
    {
        String counts = syncCountsText();
        return counts.isEmpty() ? "↻" : counts + "  ↻"; //$NON-NLS-1$
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

    private boolean confirmDiscard(String title, String question, String action)
    {
        return new MessageDialog(getSite().getShell(), title, null, question, MessageDialog.WARNING,
            new String[] {Messages.get("keepChangesAction"), action}, 0).open() == 1; //$NON-NLS-1$
    }

    private void discardUnstagedChanges()
    {
        Repository repository = selectedRepository();
        if (repository == null || isRunning(repository) || !canDiscardUnstagedChanges())
            return;
        int count = discardableUnstagedCount();
        String question = count == 1
            ? Messages.get("discardConfirm").replace("{0}", changes.unstaged().stream() //$NON-NLS-1$ //$NON-NLS-2$
                .filter(change -> !"C".equals(change.state())).findFirst().orElseThrow().path()) //$NON-NLS-1$
            : Messages.get("discardAllConfirm").replace("{0}", Integer.toString(count)); //$NON-NLS-1$ //$NON-NLS-2$
        if (count > 1)
            question += "\n\n" + Messages.get("discardUnstagedWarning"); //$NON-NLS-1$ //$NON-NLS-2$
        if (!confirmDiscard(Messages.get("discardChanges"), question, count == 1 //$NON-NLS-1$
            ? Messages.get("discardFileAction") //$NON-NLS-1$
            : Messages.get("discardAllAction").replace("{0}", Integer.toString(count)))) //$NON-NLS-1$ //$NON-NLS-2$
            return;
        OperationJob.schedule(repository, getSite().getShell(), Messages.get("discardAllChanges"), //$NON-NLS-1$
            (selected, monitor) -> WorkingChanges.discardUnstagedChanges(selected), null);
    }

    private void runPrimary()
    {
        boolean mergeReady = repositoryState == RepositoryState.MERGING_RESOLVED;
        if (!mergeReady && changes.staged().isEmpty() && changes.unstaged().isEmpty())
        {
            runAdaptiveSync();
            return;
        }
        Repository repository = selectedRepository();
        if (repository == null)
            return;
        boolean stageTracked = !mergeReady && changes.staged().isEmpty();
        if (!CommitPreparation.saveEditors(repository, getSite().getShell(),
            changes.staged().stream().map(FileChange::path).collect(java.util.stream.Collectors.toSet())))
            return;
        String message = messageField.getText().replace("\r\n", "\n").trim(); //$NON-NLS-1$ //$NON-NLS-2$
        boolean send = sendAfterCommit && hasRemote;
        OperationJob.schedule(repository, getSite().getShell(), Messages.get("commitAndPush"), //$NON-NLS-1$
            (selected, monitor) -> CommitOperations.commitAndPush(selected, message,
                stageTracked, send, false, monitor),
            (selected, monitor) -> CommitOperations.commitAndPush(selected, message,
                stageTracked, send, true, monitor), result ->
            {
                if (result.commitCreated() && !messageField.isDisposed())
                {
                    autoFilledMergeMessage = null;
                    mergeMessageEdited = false;
                    settingMergeMessage = true;
                    messageField.setText(""); //$NON-NLS-1$
                    settingMergeMessage = false;
                }
            });
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
        if (repository != null)
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
            queuedIndexChanges.addLast(new PendingIndexChange(repository, path, staged));
            runNextIndexChange();
        }
    }

    private void runNextIndexChange()
    {
        if (indexChangeRunning || queuedIndexChanges.isEmpty())
            return;
        PendingIndexChange pending = queuedIndexChanges.peekFirst();
        if (isRunning(pending.repository()))
            return;
        indexChangeRunning = true;
        OperationJob.schedule(pending.repository(), getSite().getShell(),
            Messages.get(pending.staged() ? "unstageFile" : "stageFile"), //$NON-NLS-1$ //$NON-NLS-2$
            (selected, monitor) -> pending.staged() ? WorkingChanges.unstage(selected, pending.path())
                : WorkingChanges.stage(selected, pending.path()), null, result ->
                {
                    queuedIndexChanges.removeFirstOccurrence(pending);
                    indexChangeRunning = false;
                    runNextIndexChange();
                });
    }

    private void discardFile(FileChange file)
    {
        Repository repository = selectedRepository();
        if (repository == null || isRunning(repository))
            return;
        String question = Messages.get("discardConfirm").replace("{0}", file.path()); //$NON-NLS-1$ //$NON-NLS-2$
        if (!confirmDiscard(Messages.get("discardChanges"), question, Messages.get("discardFileAction"))) //$NON-NLS-1$ //$NON-NLS-2$
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

    private void deleteUntrackedFile(FileChange file)
    {
        Repository repository = selectedRepository();
        if (repository == null || isRunning(repository))
            return;
        String question = Messages.get("deleteNewFileConfirm").replace("{0}", file.path()) + "\n\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + Messages.get("deleteNewFileWarning"); //$NON-NLS-1$
        if (!confirmDiscard(Messages.get("deleteNewFile"), question, Messages.get("deleteFileAction"))) //$NON-NLS-1$ //$NON-NLS-2$
            return;
        OperationJob.schedule(repository, getSite().getShell(), Messages.get("deleteNewFile"), //$NON-NLS-1$
            (selected, monitor) -> WorkingChanges.deleteUntrackedFile(selected, file.path()), null);
    }

    private void openFile(String path)
    {
        IFile file = workspaceFile(path);
        try
        {
            if (file != null)
                IDE.openEditor(getSite().getPage(), file);
            else if (selectedRepository() != null)
                IDE.openEditorOnFileStore(getSite().getPage(), EFS.getStore(
                    new File(selectedRepository().getWorkTree(), path).toURI()));
        }
        catch (CoreException e)
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
        OperationJob.schedule(repository, getSite().getShell(), Messages.get("hideAllChanges"), //$NON-NLS-1$
            (selected, monitor) ->
            {
                if (!RepositorySupport.isSafe(selected))
                    return new OperationResult(OperationResult.Kind.ERROR, Messages.get("busy")); //$NON-NLS-1$
                StashOperations.Result result = StashOperations.quickStash(selected, true, monitor);
                return switch (result.outcome())
                {
                    case CREATED -> new OperationResult(OperationResult.Kind.SUCCESS,
                        Messages.get("created") + result.detail() + Messages.get("createdEnd"), true); //$NON-NLS-1$ //$NON-NLS-2$
                    case NO_CHANGES -> new OperationResult(OperationResult.Kind.NO_CHANGE,
                        Messages.get("noChanges")); //$NON-NLS-1$
                    case APPLIED, NOT_FOUND, CONFLICTS, ERROR ->
                        new OperationResult(OperationResult.Kind.ERROR, result.detail());
                };
            }, null);
    }

    private void restoreLastStash()
    {
        Repository repository = selectedRepository();
        if (repository == null || isRunning(repository))
            return;
        OperationJob.schedule(repository, getSite().getShell(), Messages.get("restoreLastStash"), //$NON-NLS-1$
            (selected, monitor) ->
            {
                if (!RepositorySupport.isSafe(selected))
                    return new OperationResult(OperationResult.Kind.ERROR, Messages.get("busy")); //$NON-NLS-1$
                StashOperations.Result result = StashOperations.quickPop(selected, monitor);
                return switch (result.outcome())
                {
                    case APPLIED -> new OperationResult(OperationResult.Kind.SUCCESS,
                        Messages.get("applied"), true); //$NON-NLS-1$
                    case CONFLICTS -> new OperationResult(OperationResult.Kind.CONFLICT,
                        Messages.get("conflicts") + result.detail(), true); //$NON-NLS-1$
                    case ERROR -> new OperationResult(OperationResult.Kind.ERROR,
                        Messages.get("error") + result.detail(), true); //$NON-NLS-1$
                    case NOT_FOUND -> new OperationResult(OperationResult.Kind.NO_CHANGE,
                        Messages.get("notFound")); //$NON-NLS-1$
                    case NO_CHANGES -> new OperationResult(OperationResult.Kind.NO_CHANGE,
                        Messages.get("noChanges")); //$NON-NLS-1$
                    case CREATED -> new OperationResult(OperationResult.Kind.ERROR,
                        result.detail());
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
            (selected, monitor) -> staged ? WorkingChanges.unstageAll(selected)
                : WorkingChanges.stageAll(selected), null);
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
            try
            {
                IPath location = new Path(file.getAbsolutePath());
                String state = findState(path, staged);
                if ("C".equals(state)) //$NON-NLS-1$
                    CompareUtils.openInCompare(getSite().getPage(), repository,
                        new GitMergeEditorInput(MergeInputMode.WORKTREE, location));
                else if ("U".equals(state)) //$NON-NLS-1$
                    openFile(path);
                else
                {
                    var index = CompareUtils.getIndexTypedElement(repository, path);
                    var left = staged ? index : new LocalNonWorkspaceTypedElement(repository, location);
                    var right = staged ? CompareUtils.getHeadTypedElement(repository, path) : index;
                    CompareUtils.openInCompare(getSite().getPage(), repository,
                        new GitCompareFileRevisionEditorInput(left, right, getSite().getPage()));
                }
            }
            catch (IOException | RuntimeException e)
            {
                publish(Messages.get("openDiffFailed") + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }
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
        if (indexDiffEntry != null)
            indexDiffEntry.removeIndexDiffChangedListener(indexDiffListener);
        indexDiffEntry = null;
        indexDiffData = null;
        indexObservedRepository = null;
        if (refreshJob != null)
            refreshJob.cancel();
        if (branchImage != null && !branchImage.isDisposed())
            branchImage.dispose();
        metadataImages.values().forEach(image ->
        {
            if (!image.isDisposed())
                image.dispose();
        });
        metadataImages.clear();
        instance = null;
        super.dispose();
    }

    private static ImageDescriptor egitImage(String path)
    {
        var bundle = Platform.getBundle("org.eclipse.egit.ui"); //$NON-NLS-1$
        URL entry = bundle == null ? null : bundle.getEntry(path);
        if (entry != null)
            return ImageDescriptor.createFromURL(entry);
        return PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_OBJ_ELEMENT);
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
            {
                instance.updateRepository();
                instance.runNextIndexChange();
            }
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
        publishStatus(message, message);
    }

    public static void publishStatus(String historyMessage, String panelMessage)
    {
        Display display = Display.getDefault();
        if (display.getThread() != Thread.currentThread())
        {
            display.asyncExec(() -> publishStatus(historyMessage, panelMessage));
            return;
        }
        HISTORY.append('[').append(TIME.format(LocalTime.now())).append("] ") //$NON-NLS-1$
            .append(historyMessage).append(System.lineSeparator());
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
            instance.showFeedback(panelMessage);
        }
    }

    public static void updateProgress(String panelMessage)
    {
        Display display = Display.getDefault();
        if (display.getThread() != Thread.currentThread())
        {
            display.asyncExec(() -> updateProgress(panelMessage));
            return;
        }
        if (instance != null && instance.feedbackLabel != null && !instance.feedbackLabel.isDisposed())
            instance.showFeedback(panelMessage);
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
