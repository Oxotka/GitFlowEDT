package dev.edt.gitflow.ui.views;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

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
import org.eclipse.swt.widgets.Menu;

import dev.edt.gitflow.core.RecentHistory;

final class RecentHistoryPane extends Composite
{
    private static final Object LOAD_MORE = new Object();
    private static final String LOAD_MORE_TEXT = "Показать еще"; //$NON-NLS-1$
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"); //$NON-NLS-1$
    private final Table table;
    private final Color dotFill;
    private final Color dotOutline;
    private Function<RecentHistory.Entry, Menu> menuProvider;
    private Menu contextMenu;
    private Runnable loadMoreAction;
    private boolean loadingMore;

    RecentHistoryPane(Composite parent)
    {
        super(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        setLayout(layout);
        dotFill = new Color(getDisplay(), 220, 220, 220);
        dotOutline = new Color(getDisplay(), 110, 110, 110);
        table = new Table(this, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.BORDER);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        TableColumn content = new TableColumn(table, SWT.LEFT);
        content.setWidth(240);
        table.addListener(SWT.Resize, event -> content.setWidth(Math.max(80,
            table.getClientArea().width - 4)));
        table.addListener(SWT.MeasureItem, event -> event.height = Math.max(event.height,
            event.gc.getFontMetrics().getHeight() * 2 + 8));
        table.addListener(SWT.EraseItem, event ->
        {
            if (event.index == 0 && (event.item.getData() instanceof RecentHistory.Entry
                || event.item.getData() == LOAD_MORE))
                event.detail &= ~SWT.FOREGROUND;
        });
        table.addListener(SWT.PaintItem, event ->
        {
            if (event.item.getData() instanceof RecentHistory.Entry entry)
            {
                GraphRenderer renderer = new GraphRenderer(event.gc, event.x, event.y, dotFill, dotOutline);
                renderer.paint(entry.plot(), event.height);
                int textX = event.x + renderer.rightExtent() + 8;
                paintMessage(event.gc, textX, event.y + 3,
                    event.x + event.width - textX - 4, entry);
            }
            else if (event.item.getData() == LOAD_MORE)
            {
                Color foreground = event.gc.getForeground();
                try
                {
                    event.gc.setForeground(event.gc.getDevice().getSystemColor(
                        loadingMore ? SWT.COLOR_DARK_GRAY : SWT.COLOR_LINK_FOREGROUND));
                    String text = loadingMore ? "Загружаем…" : LOAD_MORE_TEXT; //$NON-NLS-1$
                    int x = event.x + 8;
                    int y = event.y + Math.max(0, (event.height - event.gc.getFontMetrics().getHeight()) / 2);
                    event.gc.drawText(text, x, y, true);
                    int textWidth = event.gc.textExtent(text).x;
                    event.gc.drawLine(x, y + event.gc.getFontMetrics().getHeight() - 1,
                        x + textWidth, y + event.gc.getFontMetrics().getHeight() - 1);
                }
                finally
                {
                    event.gc.setForeground(foreground);
                }
            }
        });
        table.addListener(SWT.MouseMove, event ->
        {
            TableItem item = table.getItem(new Point(event.x, event.y));
            if (item != null && item.getData() instanceof RecentHistory.Entry entry)
            {
                table.setToolTipText(entry.hash().substring(0, 8) + " · " + entry.author() + " · " //$NON-NLS-1$ //$NON-NLS-2$
                    + DATE.format(Instant.ofEpochSecond(entry.commitTime()).atZone(ZoneId.systemDefault()))
                    + "\n" + entry.message()); //$NON-NLS-1$
                table.setCursor(table.getDisplay().getSystemCursor(SWT.CURSOR_ARROW));
            }
            else if (item != null && item.getData() == LOAD_MORE)
            {
                table.setToolTipText(null);
                table.setCursor(table.getDisplay().getSystemCursor(loadingMore
                    ? SWT.CURSOR_ARROW : SWT.CURSOR_HAND));
            }
            else
            {
                table.setToolTipText(null);
                table.setCursor(table.getDisplay().getSystemCursor(SWT.CURSOR_ARROW));
            }
        });
        table.addListener(SWT.MouseUp, event ->
        {
            if (event.button == 1 && !loadingMore
                && table.getItem(new Point(event.x, event.y)) != null
                && table.getItem(new Point(event.x, event.y)).getData() == LOAD_MORE
                && loadMoreAction != null)
            {
                table.deselectAll();
                loadMoreAction.run();
            }
        });
        table.addListener(SWT.KeyDown, event ->
        {
            if (event.keyCode == SWT.CR && !loadingMore && table.getSelectionCount() == 1
                && table.getSelection()[0].getData() == LOAD_MORE && loadMoreAction != null)
                loadMoreAction.run();
        });
        table.addListener(SWT.MenuDetect, event ->
        {
            Point point = table.toControl(event.x, event.y);
            TableItem item = table.getItem(point);
            if (item == null || !(item.getData() instanceof RecentHistory.Entry entry)
                || menuProvider == null)
            {
                table.setMenu(null);
                if (contextMenu != null && !contextMenu.isDisposed())
                    contextMenu.dispose();
                contextMenu = null;
                return;
            }
            table.setSelection(item);
            if (contextMenu != null && !contextMenu.isDisposed())
                contextMenu.dispose();
            contextMenu = menuProvider.apply(entry);
            table.setMenu(contextMenu);
            if (contextMenu != null)
                contextMenu.setVisible(true);
            event.doit = false;
        });
        table.addListener(SWT.Dispose, event ->
        {
            if (contextMenu != null && !contextMenu.isDisposed())
                contextMenu.dispose();
            dotFill.dispose();
            dotOutline.dispose();
        });
    }

    void setMenuProvider(Function<RecentHistory.Entry, Menu> provider)
    {
        menuProvider = provider;
    }

    void setCommits(List<RecentHistory.Entry> commits, boolean hasMore, Runnable action)
    {
        loadMoreAction = action;
        loadingMore = false;
        table.setRedraw(false);
        try
        {
            table.removeAll();
            if (commits.isEmpty())
            {
                new TableItem(table, SWT.NONE).setText("Нет коммитов"); //$NON-NLS-1$
                return;
            }
            for (RecentHistory.Entry entry : commits)
            {
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(entry.subject() + " · " + entry.author()); //$NON-NLS-1$
                item.setData(entry);
            }
            addLoadMore(hasMore);
        }
        finally
        {
            table.setRedraw(true);
        }
    }

    void appendCommits(List<RecentHistory.Entry> commits, boolean hasMore, Runnable action)
    {
        loadMoreAction = action;
        loadingMore = false;
        table.setRedraw(false);
        try
        {
            if (table.getItemCount() > 0 && table.getItem(table.getItemCount() - 1).getData() == LOAD_MORE)
                table.getItem(table.getItemCount() - 1).dispose();
            for (RecentHistory.Entry entry : commits)
            {
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(entry.subject() + " · " + entry.author()); //$NON-NLS-1$
                item.setData(entry);
            }
            addLoadMore(hasMore);
        }
        finally
        {
            table.setRedraw(true);
        }
    }

    void setLoadingMore(boolean loading)
    {
        loadingMore = loading;
        table.redraw();
    }

    private void addLoadMore(boolean hasMore)
    {
        if (hasMore)
        {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(LOAD_MORE_TEXT);
            item.setData(LOAD_MORE);
        }
    }

    private void paintMessage(GC gc, int x, int y, int width, RecentHistory.Entry entry)
    {
        if (width <= 0)
            return;
        Color foreground = gc.getForeground();
        Color background = gc.getBackground();
        int alpha = gc.getAlpha();
        try
        {
            gc.setForeground(table.getForeground());
            String first = fit(gc, entry.subject(), width);
            String remaining = entry.subject().substring(first.length()).stripLeading();
            int lineHeight = gc.getFontMetrics().getHeight();
            gc.drawText(first, x, y, true);
            String labels = entry.labels().isEmpty() ? "" : String.join("  ↔  ", entry.labels()); //$NON-NLS-1$ //$NON-NLS-2$
            labels = ellipsize(gc, labels, Math.max(0, width / 2 - 12));
            int labelWidth = labels.isEmpty() ? 0 : gc.textExtent(labels).x + 12;
            int contentWidth = Math.max(0, width - labelWidth - (labelWidth == 0 ? 0 : 8));
            String author;
            String second;
            if (remaining.isEmpty())
            {
                second = ""; //$NON-NLS-1$
                author = ellipsize(gc, entry.author(), contentWidth);
            }
            else
            {
                author = ellipsize(gc, entry.author(), Math.min(contentWidth, Math.max(40, contentWidth / 2)));
                int authorWidth = gc.textExtent(author).x;
                second = ellipsize(gc, remaining,
                    contentWidth - authorWidth - gc.textExtent(" · ").x); //$NON-NLS-1$
            }
            if (!second.isEmpty())
                gc.drawText(second, x, y + lineHeight, true);
            gc.setAlpha(170);
            gc.drawText((second.isEmpty() ? "" : " · ") + author, //$NON-NLS-1$ //$NON-NLS-2$
                x + gc.textExtent(second).x, y + lineHeight, true);
            if (!labels.isEmpty())
            {
                int labelHeight = lineHeight + 2;
                int labelX = x + width - labelWidth;
                int labelY = y + lineHeight + 2;
                Color labelForeground = gc.getDevice().getSystemColor(SWT.COLOR_DARK_BLUE);
                Color labelBackground = gc.getDevice().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
                gc.setAlpha(255);
                gc.setBackground(labelBackground);
                gc.setForeground(labelForeground);
                gc.fillRoundRectangle(labelX, labelY, labelWidth, labelHeight, 6, 6);
                gc.drawRoundRectangle(labelX, labelY, labelWidth, labelHeight, 6, 6);
                gc.drawText(labels, labelX + 6, labelY + 1, true);
            }
        }
        finally
        {
            gc.setForeground(foreground);
            gc.setBackground(background);
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
        private final Color dotFill;
        private final Color dotOutline;
        private int rightExtent;

        GraphRenderer(GC gc, int x, int y, Color dotFill, Color dotOutline)
        {
            this.gc = gc;
            this.x = x;
            this.y = y;
            this.dotFill = dotFill;
            this.dotOutline = dotOutline;
        }

        void paint(PlotCommit<PlotLane> commit, int height)
        {
            Color foreground = gc.getForeground();
            Color background = gc.getBackground();
            int lineWidth = gc.getLineWidth();
            int antialias = gc.getAntialias();
            try
            {
                gc.setAntialias(SWT.ON);
                paintCommit(commit, height);
            }
            finally
            {
                gc.setForeground(foreground);
                gc.setBackground(background);
                gc.setLineWidth(lineWidth);
                gc.setAntialias(antialias);
            }
        }

        int rightExtent()
        {
            return rightExtent;
        }

        @Override
        protected int drawLabel(int x, int y, Ref ref)
        {
            return 0;
        }

        @Override
        protected Color laneColor(PlotLane lane)
        {
            int[] colors = { SWT.COLOR_DARK_BLUE, SWT.COLOR_DARK_YELLOW, SWT.COLOR_DARK_RED,
                SWT.COLOR_DARK_GREEN };
            return gc.getDevice().getSystemColor(colors[Math.floorMod(lane.getPosition(), colors.length)]);
        }

        @Override
        protected void drawLine(Color color, int x1, int y1, int x2, int y2, int width)
        {
            rightExtent = Math.max(rightExtent, Math.max(x1, x2));
            gc.setForeground(color);
            gc.setLineWidth(width);
            gc.drawLine(x + x1, y + y1, x + x2, y + y2);
        }

        @Override
        protected void drawCommitDot(int x, int y, int width, int height)
        {
            rightExtent = Math.max(rightExtent, x + width);
            drawDot(dotOutline, dotFill, x, y, width, height);
        }

        @Override
        protected void drawBoundaryDot(int x, int y, int width, int height)
        {
            rightExtent = Math.max(rightExtent, x + width);
            drawDot(gc.getDevice().getSystemColor(SWT.COLOR_GRAY),
                gc.getDevice().getSystemColor(SWT.COLOR_WHITE), x, y, width, height);
        }

        private void drawDot(Color outline, Color fill, int x, int y, int width, int height)
        {
            int dotX = this.x + x + 2;
            int dotY = this.y + y + 1;
            int dotWidth = width - 2;
            int dotHeight = height - 2;
            gc.setBackground(fill);
            gc.fillOval(dotX, dotY, dotWidth, dotHeight);
            gc.setForeground(outline);
            gc.setLineWidth(2);
            gc.drawOval(dotX, dotY, dotWidth, dotHeight);
        }

        @Override
        protected void drawText(String text, int x, int y)
        {
        }
    }
}
