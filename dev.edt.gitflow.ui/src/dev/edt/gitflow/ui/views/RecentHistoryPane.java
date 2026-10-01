package dev.edt.gitflow.ui.views;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revplot.AbstractPlotRenderer;
import org.eclipse.jgit.revplot.PlotCommit;
import org.eclipse.jgit.revplot.PlotLane;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import dev.edt.gitflow.core.RecentHistory;

final class RecentHistoryPane extends Composite
{
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"); //$NON-NLS-1$
    private final Table table;

    RecentHistoryPane(Composite parent)
    {
        super(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 3;
        setLayout(layout);
        Label title = new Label(this, SWT.NONE);
        title.setText("ИСТОРИЯ · 30 последних"); //$NON-NLS-1$
        table = new Table(this, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.BORDER);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        TableColumn graph = new TableColumn(table, SWT.LEFT);
        graph.setWidth(78);
        TableColumn subject = new TableColumn(table, SWT.LEFT);
        subject.setWidth(160);
        TableColumn author = new TableColumn(table, SWT.LEFT);
        author.setWidth(100);
        table.addListener(SWT.Resize, event -> subject.setWidth(Math.max(80,
            table.getClientArea().width - graph.getWidth() - author.getWidth() - 4)));
        table.addListener(SWT.PaintItem, event ->
        {
            if (event.index == 0 && event.item.getData() instanceof RecentHistory.Entry entry)
                new GraphRenderer(event.gc, event.x, event.y).paint(entry.plot(), event.height);
        });
        table.addListener(SWT.MouseMove, event ->
        {
            TableItem item = table.getItem(new Point(event.x, event.y));
            if (item != null && item.getData() instanceof RecentHistory.Entry entry)
                table.setToolTipText(entry.hash().substring(0, 8) + " · " + entry.author() + " · " //$NON-NLS-1$ //$NON-NLS-2$
                    + DATE.format(Instant.ofEpochSecond(entry.commitTime()).atZone(ZoneId.systemDefault()))
                    + "\n" + entry.message()); //$NON-NLS-1$
            else
                table.setToolTipText(null);
        });
    }

    void setCommits(List<RecentHistory.Entry> commits)
    {
        table.setRedraw(false);
        try
        {
            table.removeAll();
            if (commits.isEmpty())
            {
                new TableItem(table, SWT.NONE).setText(1, "Нет коммитов"); //$NON-NLS-1$
                return;
            }
            for (RecentHistory.Entry entry : commits)
            {
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(new String[] { "", entry.subject(), entry.author() }); //$NON-NLS-1$
                item.setData(entry);
            }
        }
        finally
        {
            table.setRedraw(true);
        }
    }

    private static final class GraphRenderer extends AbstractPlotRenderer<PlotLane, Color>
    {
        private final GC gc;
        private final int x;
        private final int y;
        private Color dotColor;

        GraphRenderer(GC gc, int x, int y)
        {
            this.gc = gc;
            this.x = x;
            this.y = y;
        }

        void paint(PlotCommit<PlotLane> commit, int height)
        {
            Color foreground = gc.getForeground();
            Color background = gc.getBackground();
            int lineWidth = gc.getLineWidth();
            dotColor = laneColor(commit.getLane());
            try
            {
                paintCommit(commit, height);
            }
            finally
            {
                gc.setForeground(foreground);
                gc.setBackground(background);
                gc.setLineWidth(lineWidth);
            }
        }

        @Override
        protected int drawLabel(int x, int y, Ref ref)
        {
            return 0;
        }

        @Override
        protected Color laneColor(PlotLane lane)
        {
            int[] colors = { SWT.COLOR_DARK_BLUE, SWT.COLOR_DARK_GREEN, SWT.COLOR_DARK_MAGENTA,
                SWT.COLOR_DARK_CYAN };
            return gc.getDevice().getSystemColor(colors[Math.floorMod(lane.getPosition(), colors.length)]);
        }

        @Override
        protected void drawLine(Color color, int x1, int y1, int x2, int y2, int width)
        {
            gc.setForeground(color);
            gc.setLineWidth(width);
            gc.drawLine(x + x1, y + y1, x + x2, y + y2);
        }

        @Override
        protected void drawCommitDot(int x, int y, int width, int height)
        {
            gc.setBackground(dotColor);
            gc.fillOval(this.x + x, this.y + y, width, height);
        }

        @Override
        protected void drawBoundaryDot(int x, int y, int width, int height)
        {
            gc.drawOval(this.x + x, this.y + y, width, height);
        }

        @Override
        protected void drawText(String text, int x, int y)
        {
        }
    }
}
