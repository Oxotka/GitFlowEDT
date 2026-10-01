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
        setLayout(layout);
        table = new Table(this, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.BORDER);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        TableColumn graph = new TableColumn(table, SWT.LEFT);
        graph.setWidth(56);
        TableColumn message = new TableColumn(table, SWT.LEFT);
        message.setWidth(240);
        table.addListener(SWT.Resize, event -> message.setWidth(Math.max(80,
            table.getClientArea().width - graph.getWidth() - 4)));
        table.addListener(SWT.MeasureItem, event -> event.height = Math.max(event.height,
            event.gc.getFontMetrics().getHeight() * 2 + 8));
        table.addListener(SWT.EraseItem, event ->
        {
            if (event.index == 1 && event.item.getData() instanceof RecentHistory.Entry)
                event.detail &= ~SWT.FOREGROUND;
        });
        table.addListener(SWT.PaintItem, event ->
        {
            if (event.item.getData() instanceof RecentHistory.Entry entry)
            {
                if (event.index == 0)
                    new GraphRenderer(event.gc, event.x, event.y).paint(entry.plot(), event.height);
                else if (event.index == 1)
                    paintMessage(event.gc, event.x + 4, event.y + 3,
                        message.getWidth() - 8, entry,
                        table.getSelectionCount() > 0 && event.item == table.getSelection()[0]);
            }
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
                item.setText(new String[] { "", entry.subject() + " · " + entry.author() }); //$NON-NLS-1$ //$NON-NLS-2$
                item.setData(entry);
            }
        }
        finally
        {
            table.setRedraw(true);
        }
    }

    private void paintMessage(GC gc, int x, int y, int width, RecentHistory.Entry entry,
        boolean selected)
    {
        if (width <= 0)
            return;
        Color foreground = gc.getForeground();
        int alpha = gc.getAlpha();
        try
        {
            gc.setForeground(selected ? gc.getDevice().getSystemColor(SWT.COLOR_LIST_SELECTION_TEXT)
                : table.getForeground());
            String first = fit(gc, entry.subject(), width);
            String remaining = entry.subject().substring(first.length()).stripLeading();
            int lineHeight = gc.getFontMetrics().getHeight();
            gc.drawText(first, x, y, true);
            String author = ellipsize(gc, entry.author(), Math.min(width, Math.max(40, width / 2)));
            int authorWidth = gc.textExtent(author).x;
            String second = ellipsize(gc, remaining,
                width - authorWidth - gc.textExtent(" · ").x); //$NON-NLS-1$
            if (!second.isEmpty())
                gc.drawText(second, x, y + lineHeight, true);
            gc.setAlpha(selected ? 255 : 170);
            gc.drawText((second.isEmpty() ? "" : " · ") + author, //$NON-NLS-1$ //$NON-NLS-2$
                x + gc.textExtent(second).x, y + lineHeight, true);
        }
        finally
        {
            gc.setForeground(foreground);
            gc.setAlpha(alpha);
        }
    }

    private static String fit(GC gc, String value, int width)
    {
        int end = value.length();
        while (end > 0 && gc.textExtent(value.substring(0, end)).x > width)
            end = value.offsetByCodePoints(end, -1);
        return value.substring(0, end);
    }

    private static String ellipsize(GC gc, String value, int width)
    {
        if (width <= 0)
            return ""; //$NON-NLS-1$
        if (gc.textExtent(value).x <= width)
            return value;
        String ellipsis = "…"; //$NON-NLS-1$
        return fit(gc, value, width - gc.textExtent(ellipsis).x).stripTrailing() + ellipsis;
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
