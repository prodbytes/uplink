package org.uplink.report;

import static dev.tamboui.toolkit.Toolkit.barChart;
import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.lineGauge;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.tabs;
import static dev.tamboui.toolkit.Toolkit.text;

import java.io.PrintStream;
import java.net.URI;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.uplink.VersionProvider;
import org.uplink.crawl.CrawlStats;
import org.uplink.crawl.Crawler;
import org.uplink.crawl.LinkResult;

import dev.tamboui.backend.panama.PanamaBackendProvider;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Backend;
import dev.tamboui.terminal.BackendFactory;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.bindings.Actions;
import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.bindings.KeyTrigger;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.barchart.Bar;
import dev.tamboui.widgets.common.ScrollBarPolicy;

/**
 * Interactive monitoring dashboard: crawls the site in a loop until Ctrl+C. Three tabs
 * show the summary (broken and slow links plus an event log of what changed), every
 * bad link with its status code, and the site's pages by latency with a histogram.
 * The report of the last completed pass is printed once it exits.
 */
public final class Dashboard {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Rows of the event log panel, borders included. */
    private static final int EVENT_ROWS = 14;
    /** Rows a Page Up / Page Down jumps in the scrollable tabs. */
    private static final int PAGE_ROWS = 10;

    enum Tab {
        SUMMARY("Summary"), BROKEN("Broken"), LATENCY("Latency");

        final String title;

        Tab(String title) {
            this.title = title;
        }
    }

    private final URI root;
    private final List<URI> sites;
    private final Crawler.Options options;
    private final Duration interval;
    private final Monitor monitor;
    private final CountDownLatch stop = new CountDownLatch(1);
    private volatile Crawler current;
    private volatile Crawler lastCompleted;
    private volatile int pass;
    private volatile long nextPassAtNanos;
    private String finalReport = "";
    // Only touched by the render thread, which also handles key events.
    private Tab tab = Tab.SUMMARY;
    private int brokenCursor;
    private int latencyCursor;

    public Dashboard(URI root, Crawler.Options options, Duration interval, Duration slowThreshold) {
        this(List.of(root), options, interval, slowThreshold);
    }

    /** @param sites the start URL, followed by any other sites whose pages are crawled too */
    public Dashboard(List<URI> sites, Crawler.Options options, Duration interval, Duration slowThreshold) {
        this.root = sites.getFirst();
        this.sites = sites;
        this.options = options;
        this.interval = interval;
        this.monitor = new Monitor(slowThreshold);
    }

    /**
     * Takes over the terminal. The Panama backend is created directly rather than
     * discovered through {@code ServiceLoader}, which Quarkus native images do not
     * populate automatically. Only Ctrl+C quits: the monitor runs until stopped.
     * Tabs are switched with 1-3, Tab / Shift+Tab or the left and right arrows.
     *
     * @throws Exception if the terminal cannot host a TUI
     */
    public static ToolkitRunner openTerminal() throws Exception {
        Backend backend = BackendFactory.recordIfEnabled(new PanamaBackendProvider().create());
        TuiConfig config = TuiConfig.builder()
                .backend(backend)
                .bindings(BindingSets.standard().toBuilder()
                        .unbind(Actions.QUIT)
                        .bind(KeyTrigger.ctrl('c'), Actions.QUIT)
                        .build())
                .tickRate(Duration.ofMillis(200))
                .build();
        return ToolkitRunner.create(config);
    }

    /**
     * Runs crawl passes in a loop on a virtual thread while the dashboard is shown,
     * until the user presses Ctrl+C.
     *
     * @return whether the last completed pass found broken links
     */
    public boolean run(ToolkitRunner runner, PrintStream out) throws Exception {
        Thread loop = Thread.ofVirtual().name("uplink-monitor").start(this::loop);
        try (runner) {
            runner.setWindowTitle("uplink " + root);
            runner.eventRouter().addGlobalHandler(this::handle);
            runner.run(this::render);
        } finally {
            stop.countDown();
            Crawler running = current;
            if (running != null) {
                running.cancel();
            }
            loop.join();
        }
        Crawler report = lastCompleted != null ? lastCompleted : current;
        Monitor.PassSummary last = monitor.lastPass();
        StringBuilder text = new StringBuilder(last == null
                ? "uplink stopped during the first pass; partial report:"
                : "uplink stopped after " + last.number() + " completed pass(es); report of pass #" + last.number() + ":")
                .append(System.lineSeparator());
        if (report != null) {
            text.append(Report.totals(report.root(), report.stats(), report.results(), report.isCancelled()));
        }
        finalReport = text.toString();
        out.print(finalReport);
        out.flush();
        return lastCompleted != null && lastCompleted.stats().broken() > 0;
    }

    /** What {@link #run} printed when the dashboard closed. */
    public String finalReport() {
        return finalReport;
    }

    private void loop() {
        while (stop.getCount() > 0) {
            int number = ++pass;
            Crawler crawler = new Crawler(sites, options, monitor);
            current = crawler;
            monitor.passStarted(number);
            try {
                crawler.run();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                monitor.passFailed(number, e);
            }
            if (stop.getCount() == 0) {
                return;
            }
            if (!crawler.isCancelled()) {
                monitor.passFinished(number, crawler);
                lastCompleted = crawler;
            }
            nextPassAtNanos = System.nanoTime() + interval.toNanos();
            try {
                if (stop.await(interval.toMillis(), TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                return;
            } finally {
                nextPassAtNanos = 0;
            }
        }
    }

    // ---- keys ----------------------------------------------------------------

    EventResult handle(Event event) {
        if (!(event instanceof KeyEvent key)) {
            return EventResult.UNHANDLED;
        }
        Tab[] all = Tab.values();
        if (key.isChar('1') || key.isChar('2') || key.isChar('3')) {
            tab = all[key.character() - '1'];
        } else if (key.isRight() || key.isFocusNext()) {
            tab = all[(tab.ordinal() + 1) % all.length];
        } else if (key.isLeft() || key.isFocusPrevious()) {
            tab = all[(tab.ordinal() + all.length - 1) % all.length];
        } else if (tab != Tab.SUMMARY && scroll(key)) {
            return EventResult.HANDLED;
        } else {
            return EventResult.UNHANDLED;
        }
        return EventResult.HANDLED;
    }

    /** Moves the cursor of the current list tab; the list scrolls to keep it visible. */
    private boolean scroll(KeyEvent key) {
        int cursor = tab == Tab.BROKEN ? brokenCursor : latencyCursor;
        if (key.isUp()) {
            cursor--;
        } else if (key.isDown()) {
            cursor++;
        } else if (key.isPageUp()) {
            cursor -= PAGE_ROWS;
        } else if (key.isPageDown()) {
            cursor += PAGE_ROWS;
        } else if (key.isHome()) {
            cursor = 0;
        } else if (key.isEnd()) {
            cursor = Integer.MAX_VALUE;
        } else {
            return false;
        }
        // Clamped against the list size at render time, which changes as the crawl runs.
        cursor = Math.max(0, cursor);
        if (tab == Tab.BROKEN) {
            brokenCursor = cursor;
        } else {
            latencyCursor = cursor;
        }
        return true;
    }

    Tab tab() {
        return tab;
    }

    Monitor monitor() {
        return monitor;
    }

    // ---- view ----------------------------------------------------------------

    Element render() {
        Crawler crawler = current;
        StyledElement<?> body = switch (tab) {
            case SUMMARY -> column(
                    row(brokenPanel().percent(60), slowPanel().fill()).fill(),
                    eventPanel());
            case BROKEN -> brokenTab();
            case LATENCY -> latencyTab();
        };
        return column(
                tabBar(),
                header(crawler),
                progress(crawler),
                body.fill(),
                footer());
    }

    /** Key help and pass state on the left, the version dimmed in the bottom-right corner. */
    private Element footer() {
        String version = VersionProvider.version();
        version = (version.equals("dev") ? version : "v" + version) + " ";
        return row(
                text(" 1-3 / ←→ switch tab   ↑↓ PgUp PgDn scroll   Ctrl+C stop   |   " + state()).dim().fill(),
                text(version).fg(Color.DARK_GRAY).length(version.length()))
                .length(1);
    }

    private Element tabBar() {
        List<String> titles = new ArrayList<>();
        for (Tab t : Tab.values()) {
            String count = switch (t) {
                case SUMMARY -> "";
                case BROKEN -> " (" + monitor.badLinks().size() + ")";
                case LATENCY -> " (" + monitor.pageLatencies().size() + ")";
            };
            titles.add((t.ordinal() + 1) + " " + t.title + count);
        }
        return tabs(titles)
                .selected(tab.ordinal())
                .highlightColor(Color.CYAN)
                .divider("│")
                .padding(" ", " ")
                .length(1);
    }

    private String state() {
        long next = nextPassAtNanos;
        if (next != 0) {
            long seconds = Math.max(0, TimeUnit.NANOSECONDS.toSeconds(next - System.nanoTime()));
            return "pass #" + pass + " done, next pass in " + seconds + "s";
        }
        return "pass #" + pass + " running";
    }

    private Element header(Crawler crawler) {
        Line target = Line.from(
                Span.raw(" Target  ").gray(),
                Span.raw(root.toString()).bold().cyan(),
                Span.raw("   pass #" + pass).white().bold(),
                Span.raw(nextPassAtNanos == 0 ? "   crawling" : "   waiting").fg(nextPassAtNanos == 0 ? Color.YELLOW : Color.GREEN));

        Line now;
        if (crawler != null && nextPassAtNanos == 0) {
            CrawlStats s = crawler.stats();
            URI checking = monitor.lastStarted();
            now = Line.from(
                    Span.raw(" " + s.checked() + " checked").white(),
                    Span.raw("   " + s.inFlight() + " in flight").white(),
                    Span.raw("   " + s.queued() + " queued").white(),
                    Span.raw("   " + s.pages() + " pages").white(),
                    Span.raw("   " + s.requests() + " requests").white(),
                    Span.raw("   " + Report.duration(s.elapsed())).dim(),
                    Span.raw(checking == null ? "" : "   " + checking).dim());
        } else {
            now = Line.from(Span.raw(" idle").dim());
        }

        Monitor.PassSummary last = monitor.lastPass();
        Line previous;
        if (last == null) {
            previous = Line.from(Span.raw(" no pass completed yet").dim());
        } else {
            CrawlStats s = last.stats();
            previous = Line.from(
                    Span.raw(" #" + last.number() + "  ").white(),
                    Span.raw(s.ok() + " good").green().bold(),
                    Span.raw("   " + s.broken() + " broken").fg(s.broken() > 0 ? Color.RED : Color.GREEN).bold(),
                    Span.raw("   " + s.blocked() + " unverified").fg(s.blocked() > 0 ? Color.YELLOW : Color.GREEN),
                    Span.raw("   " + last.slow() + " slow (≥ " + monitor.slowThreshold().toMillis() + "ms)").fg(last.slow() > 0 ? Color.MAGENTA : Color.GREEN),
                    Span.raw("   " + s.pages() + " pages   " + s.requests() + " requests").white(),
                    Span.raw("   took " + Report.duration(s.elapsed()) + ", finished " + last.finishedAt().format(TIME)).dim());
        }
        return panel(richText(Text.from(target, now, previous)))
                .title(" uplink ")
                .rounded()
                .borderColor(Color.CYAN)
                .length(5);
    }

    private Element progress(Crawler crawler) {
        if (crawler == null || nextPassAtNanos != 0) {
            long total = Math.max(1, interval.toNanos());
            long left = Math.max(0, nextPassAtNanos - System.nanoTime());
            return lineGauge(nextPassAtNanos == 0 ? 0.0 : 1.0 - (double) left / total)
                    .label(" next pass ")
                    .filledColor(Color.GREEN)
                    .length(1);
        }
        CrawlStats s = crawler.stats();
        double ratio = Math.min(1.0, (double) s.checked() / Math.max(1, s.discovered()));
        return lineGauge(ratio)
                .label(String.format(" %d / %d links ", s.checked(), s.discovered()))
                .filledColor(Color.CYAN)
                .length(1);
    }

    private StyledElement<?> brokenPanel() {
        List<LinkResult> bad = monitor.badLinks();
        if (bad.isEmpty()) {
            return panel(text(monitor.lastPass() == null ? " None found so far" : " All links OK").green())
                    .title(" Broken links ")
                    .rounded()
                    .borderColor(Color.GREEN);
        }
        List<StyledElement<?>> rows = new ArrayList<>(bad.size());
        long broken = 0;
        for (LinkResult r : bad) {
            boolean isBroken = r.outcome() == LinkResult.Outcome.BROKEN;
            broken += isBroken ? 1 : 0;
            Color color = isBroken ? Color.RED : Color.YELLOW;
            List<Span> spans = new ArrayList<>();
            spans.add(Span.raw(isBroken ? " ✗ " : " ! ").fg(color).bold());
            spans.add(Span.raw(pad(r.statusLabel(), 4)).fg(color).bold());
            spans.add(Span.raw(r.url().toString()).hyperlink(r.url().toString()));
            spans.add(Span.raw("  " + r.detail()).fg(isBroken ? Color.LIGHT_RED : Color.LIGHT_YELLOW));
            rows.add(richText(Text.from(Line.from(spans), foundOn(r, 7))).ellipsis());
        }
        return list(rows.toArray(StyledElement<?>[]::new))
                .displayOnly()
                .title(String.format(" Broken links: %d broken, %d unverified ", broken, bad.size() - broken))
                .rounded()
                .borderColor(broken > 0 ? Color.RED : Color.YELLOW);
    }

    /** Second row line under a bad link: the page it was found on, indented to line up with the URL. */
    private static Line foundOn(LinkResult r, int indent) {
        String pad = " ".repeat(indent);
        if (r.referrer() == null) {
            return Line.from(Span.raw(pad + "start URL").dim());
        }
        String page = r.referrer().toString();
        return Line.from(Span.raw(pad + "on ").dim(), Span.raw(page).hyperlink(page).dim());
    }

    private StyledElement<?> slowPanel() {
        List<LinkResult> slow = monitor.slowLinks();
        String title = " Slow links (≥ " + monitor.slowThreshold().toMillis() + "ms): " + slow.size() + " ";
        if (slow.isEmpty()) {
            return panel(text(" None").green()).title(title).rounded().borderColor(Color.GREEN);
        }
        List<StyledElement<?>> rows = new ArrayList<>(slow.size());
        for (LinkResult r : slow) {
            rows.add(richText(Text.from(Line.from(
                    Span.raw(String.format(" %6dms ", r.elapsed().toMillis())).magenta().bold(),
                    Span.raw(r.internal() ? "int " : "ext ").fg(r.internal() ? Color.CYAN : Color.GRAY),
                    Span.raw(r.url().toString()).hyperlink(r.url().toString())))).ellipsis());
        }
        return list(rows.toArray(StyledElement<?>[]::new))
                .displayOnly()
                .title(title)
                .rounded()
                .borderColor(Color.MAGENTA);
    }

    private StyledElement<?> brokenTab() {
        List<LinkResult> bad = monitor.badLinks();
        if (bad.isEmpty()) {
            return panel(text(monitor.lastPass() == null ? " None found so far" : " All links OK").green())
                    .title(" Broken links ")
                    .rounded()
                    .borderColor(Color.GREEN);
        }
        Map<String, Integer> byCode = new TreeMap<>();
        int urlWidth = 0;
        for (LinkResult r : bad) {
            byCode.merge(r.statusLabel(), 1, Integer::sum);
            urlWidth = Math.max(urlWidth, r.url().toString().length());
        }
        urlWidth = Math.min(urlWidth, 70);
        List<StyledElement<?>> rows = new ArrayList<>(bad.size());
        long broken = 0;
        for (LinkResult r : bad) {
            boolean isBroken = r.outcome() == LinkResult.Outcome.BROKEN;
            broken += isBroken ? 1 : 0;
            Color color = isBroken ? Color.RED : Color.YELLOW;
            String url = r.url().toString();
            List<Span> spans = new ArrayList<>();
            spans.add(Span.raw(pad(r.statusLabel(), 4)).fg(color).bold());
            spans.add(Span.raw(pad(isBroken ? "broken" : "unverified", 11)).fg(color));
            spans.add(Span.raw(pad(url, urlWidth)).hyperlink(url));
            spans.add(Span.raw("  " + r.detail()).fg(isBroken ? Color.LIGHT_RED : Color.LIGHT_YELLOW));
            rows.add(richText(Text.from(Line.from(spans), foundOn(r, 15))).ellipsis());
        }
        StringBuilder codes = new StringBuilder();
        byCode.forEach((code, n) -> codes.append(codes.isEmpty() ? "" : ", ").append(n).append(" × ").append(code));
        brokenCursor = Math.min(brokenCursor, bad.size() - 1);
        return list(rows.toArray(StyledElement<?>[]::new))
                .selected(brokenCursor)
                .autoScroll()
                .scrollbar(ScrollBarPolicy.AS_NEEDED)
                .highlightSymbol("▶ ")
                .highlightColor(Color.WHITE)
                .title(String.format(" %d broken, %d unverified: %s ", broken, bad.size() - broken, codes))
                .rounded()
                .borderColor(broken > 0 ? Color.RED : Color.YELLOW);
    }

    private StyledElement<?> latencyTab() {
        List<LinkResult> pages = monitor.pageLatencies();
        if (pages.isEmpty()) {
            return panel(text(" No pages checked yet").dim())
                    .title(" Pages by latency ")
                    .rounded()
                    .borderColor(Color.DARK_GRAY);
        }
        long slowMillis = monitor.slowThreshold().toMillis();
        List<StyledElement<?>> rows = new ArrayList<>(pages.size());
        for (LinkResult r : pages) {
            long millis = r.elapsed().toMillis();
            Color color = r.isBad() ? Color.RED : millis >= slowMillis ? Color.MAGENTA : Color.GREEN;
            String url = r.url().toString();
            rows.add(richText(Text.from(Line.from(
                    Span.raw(String.format("%7dms ", millis)).fg(color).bold(),
                    Span.raw(pad(r.statusLabel(), 4)).fg(r.isBad() ? Color.RED : Color.GRAY),
                    Span.raw(url).hyperlink(url)))).ellipsis());
        }
        latencyCursor = Math.min(latencyCursor, pages.size() - 1);
        String title = String.format(" %d pages, slowest first: p50 %dms, p95 %dms, max %dms ",
                pages.size(), percentile(pages, 50), percentile(pages, 95), pages.getFirst().elapsed().toMillis());
        Element list = list(rows.toArray(StyledElement<?>[]::new))
                .selected(latencyCursor)
                .autoScroll()
                .scrollbar(ScrollBarPolicy.AS_NEEDED)
                .highlightSymbol("▶ ")
                .highlightColor(Color.WHITE)
                .title(title)
                .rounded()
                .borderColor(Color.CYAN)
                .fill();

        List<Monitor.Bucket> buckets = Monitor.histogram(pages);
        Bar[] bars = new Bar[buckets.size()];
        for (int i = 0; i < bars.length; i++) {
            Monitor.Bucket b = buckets.get(i);
            bars[i] = Bar.builder()
                    .value(b.count())
                    // The count goes in the label: the chart has no room to print it after the longest bar.
                    .label(String.format("%6s %4d", b.label(), b.count()))
                    .textValue("")
                    .style(Style.EMPTY.fg(b.upperMillis() <= slowMillis ? Color.CYAN : Color.MAGENTA))
                    .build();
        }
        Element histogram = barChart()
                .data(bars)
                .horizontal()
                .barWidth(1)
                .barGap(1)
                .title(" Latency histogram ")
                .rounded()
                .borderColor(Color.CYAN)
                .length(36);
        return row(list, histogram);
    }

    /** Nearest-rank percentile of latencies sorted slowest first. */
    static long percentile(List<LinkResult> slowestFirst, int p) {
        int rank = (int) Math.ceil(p / 100.0 * slowestFirst.size());
        return slowestFirst.get(slowestFirst.size() - Math.max(1, rank)).elapsed().toMillis();
    }

    private Element eventPanel() {
        List<Monitor.Event> events = monitor.events();
        List<StyledElement<?>> rows = new ArrayList<>(events.size());
        for (Monitor.Event e : events) {
            Color color = switch (e.kind()) {
                case PASS -> Color.CYAN;
                case BROKEN, ERROR -> Color.RED;
                case UNVERIFIED -> Color.YELLOW;
                case RECOVERED -> Color.GREEN;
                case SLOW -> Color.MAGENTA;
                case GONE -> Color.GRAY;
            };
            rows.add(richText(Text.from(Line.from(
                    Span.raw(" " + e.time().format(TIME) + " ").dim(),
                    Span.raw(pad(e.kind().name(), 11)).fg(color).bold(),
                    Span.raw(e.message()).fg(e.kind() == Monitor.Kind.PASS ? Color.WHITE : Color.GRAY)))).ellipsis());
        }
        return list(rows.toArray(StyledElement<?>[]::new))
                .displayOnly()
                .scrollToEnd()
                .title(" Events ")
                .rounded()
                .borderColor(Color.DARK_GRAY)
                .length(EVENT_ROWS);
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
