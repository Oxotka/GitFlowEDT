package dev.edt.gitflow.ui.views;

import java.io.File;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import dev.edt.gitflow.core.PullOperations;
import dev.edt.gitflow.core.RepositorySupport;
import dev.edt.gitflow.core.RepositoryOverview;
import dev.edt.gitflow.ui.handlers.Messages;
import dev.edt.gitflow.ui.handlers.OperationJob;
import dev.edt.gitflow.ui.handlers.Repositories;

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
            scheduleOverview();
    });
    private Composite container;
    private Combo repositoryCombo;
    private Label branchLabel;
    private Label statusLabel;
    private Button pullButton;
    private Button pushButton;
    private Button stagingButton;
    private Text output;
    private boolean canSync;
    private Job overviewJob;
    private int overviewGeneration;

    @Override
    public void createPartControl(Composite parent)
    {
        instance = this;
        container = parent;
        parent.setLayout(new GridLayout(3, false));
        new Label(parent, SWT.NONE).setText(Messages.get("repositoryLabel")); //$NON-NLS-1$
        repositoryCombo = new Combo(parent, SWT.DROP_DOWN | SWT.READ_ONLY);
        repositoryCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        repositoryCombo.addListener(SWT.Selection, event -> selectRepository(selectedRepository()));
        Button refresh = new Button(parent, SWT.PUSH);
        refresh.setText(Messages.get("refreshRepositories")); //$NON-NLS-1$
        refresh.addListener(SWT.Selection, event -> loadRepositories());

        branchLabel = new Label(parent, SWT.WRAP);
        branchLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        statusLabel = new Label(parent, SWT.WRAP);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        statusLabel.setToolTipText(Messages.get("statusHint")); //$NON-NLS-1$
        pullButton = new Button(parent, SWT.PUSH);
        pullButton.setText(Messages.get("pullJob")); //$NON-NLS-1$
        pullButton.setToolTipText(Messages.get("pullHint")); //$NON-NLS-1$
        pullButton.addListener(SWT.Selection, event -> run(false));
        pushButton = new Button(parent, SWT.PUSH);
        pushButton.setText(Messages.get("smartPushJob")); //$NON-NLS-1$
        pushButton.setToolTipText(Messages.get("pushHint")); //$NON-NLS-1$
        pushButton.addListener(SWT.Selection, event -> run(true));
        stagingButton = new Button(parent, SWT.PUSH);
        stagingButton.setText(Messages.get("openStaging")); //$NON-NLS-1$
        stagingButton.setToolTipText(Messages.get("stagingHint")); //$NON-NLS-1$
        stagingButton.addListener(SWT.Selection, event -> openStaging());

        output = new Text(parent, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.WRAP);
        output.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1));
        output.setText(HISTORY.toString());
        Button clear = new Button(parent, SWT.PUSH);
        clear.setText(Messages.get("clearHistory")); //$NON-NLS-1$
        clear.addListener(SWT.Selection, event ->
        {
            HISTORY.setLength(0);
            output.setText(""); //$NON-NLS-1$
        });
        loadRepositories();
        getSite().setSelectionProvider(selectionProvider);
        getSite().getPage().addSelectionListener(selectionListener);
        ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener,
            IResourceChangeEvent.POST_CHANGE);
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
        if (indexOf(previous) < 0)
            previous = null;
        Repository candidate = previous != null ? previous : preferredRepository;
        if (indexOf(candidate) < 0)
            candidate = null;
        if (candidate == null)
            candidate = Repositories.context(getSite().getPage());
        if (candidate == null && !repositories.isEmpty())
            candidate = repositories.get(0);
        selectRepository(candidate);
    }

    private void selectionChanged(IWorkbenchPart part, ISelection selection)
    {
        if (part != this)
        {
            Repository repository = Repositories.fromSelection(selection);
            if (repository != null)
            {
                if (indexOf(repository) < 0)
                    loadRepositories();
                selectRepository(repository);
            }
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
        updateRepository();
    }

    private int indexOf(Repository repository)
    {
        if (repository != null)
        {
            for (int i = 0; i < repositories.size(); i++)
            {
                if (repositories.get(i).getDirectory().equals(repository.getDirectory()))
                    return i;
            }
        }
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
        if (repository == null)
            branchLabel.setText(Messages.get("chooseRepository")); //$NON-NLS-1$
        else
        {
            preferredRepository = repository;
            try
            {
                String branch = repository.getBranch();
                BranchConfig config = new BranchConfig(repository.getConfig(), branch);
                String upstream = config.getRemoteTrackingBranch();
                canSync = upstream != null || repository.getConfig().getString(
                    "remote", "origin", "url") != null; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                branchLabel.setText(branch + "  →  " //$NON-NLS-1$
                    + (upstream == null ? Messages.get(canSync ? "noUpstream" : "noOrigin") //$NON-NLS-1$ //$NON-NLS-2$
                        : upstream));
            }
            catch (IOException e)
            {
                canSync = false;
                branchLabel.setText(e.getMessage());
            }
        }
        if (repository == null)
        {
            canSync = false;
        }
        boolean enabled = repository != null && canSync && !RUNNING.containsKey(repository.getDirectory());
        pullButton.setEnabled(enabled);
        pushButton.setEnabled(enabled);
        stagingButton.setEnabled(repository != null);
        scheduleOverview();
        container.layout(true, true);
    }

    private void scheduleOverview()
    {
        if (overviewJob != null)
            overviewJob.cancel();
        int generation = ++overviewGeneration;
        Repository repository = selectedRepository();
        if (repository == null)
        {
            statusLabel.setText(""); //$NON-NLS-1$
            return;
        }
        if (RUNNING.containsKey(repository.getDirectory()))
            return;
        statusLabel.setText(Messages.get("statusReading")); //$NON-NLS-1$
        overviewJob = new Job(Messages.get("statusReading")) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor)
            {
                String status;
                try
                {
                    RepositoryOverview overview = RepositoryOverview.read(repository);
                    status = Messages.get("changedFiles") + " " + overview.changedFiles() + "   " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + (overview.incoming() < 0 ? Messages.get("noUpstream") //$NON-NLS-1$
                            : "↓" + overview.incoming() + "  ↑" + overview.outgoing()); //$NON-NLS-1$ //$NON-NLS-2$
                }
                catch (Exception e)
                {
                    status = Messages.get("statusUnavailable") + " " + e.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
                }
                String result = status;
                onUi(() ->
                {
                    if (instance == GitFlowView.this && generation == overviewGeneration)
                    {
                        statusLabel.setText(result);
                        container.layout(true, true);
                    }
                });
                return Status.OK_STATUS;
            }
        };
        overviewJob.setSystem(true);
        overviewJob.schedule(350);
    }

    private void openStaging()
    {
        try
        {
            getSite().getPage().showView("org.eclipse.egit.ui.StagingView"); //$NON-NLS-1$
        }
        catch (PartInitException e)
        {
            publish(Messages.get("stagingUnavailable") + " " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void run(boolean push)
    {
        Repository repository = selectedRepository();
        if (repository != null)
            OperationJob.schedule(repository, getSite().getShell(),
                Messages.get(push ? "smartPushJob" : "pullJob"), //$NON-NLS-1$ //$NON-NLS-2$
                push ? PullOperations::smartPush : PullOperations::smartPull,
                push ? (selected, monitor) -> PullOperations.smartPush(selected, true, monitor)
                    : (selected, monitor) -> PullOperations.smartPull(selected, true, monitor));
    }

    @Override
    public void setFocus()
    {
        if (repositoryCombo != null && !repositoryCombo.isDisposed())
        {
            loadRepositories();
            repositoryCombo.setFocus();
        }
    }

    @Override
    public void dispose()
    {
        getSite().getPage().removeSelectionListener(selectionListener);
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
        if (overviewJob != null)
            overviewJob.cancel();
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
                instance.updateRepository();
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
            RUNNING.computeIfPresent(repository.getDirectory(), (key, count) -> count == 1 ? null : count - 1);
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
            if (window == null)
                return;
            IWorkbenchPage page = window.getActivePage();
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
        if (instance != null && instance.output != null && !instance.output.isDisposed())
        {
            instance.output.setText(HISTORY.toString());
            instance.output.setSelection(instance.output.getCharCount());
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
