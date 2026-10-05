package org.uplink.web;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.uplink.crawl.AsyncCrawler;
import org.uplink.crawl.CrawlListener;
import org.uplink.crawl.Crawler;
import org.uplink.crawl.LinkResult;
import org.uplink.report.Monitor;
import org.uplink.report.PassReport;
import org.uplink.report.Report;

/**
 * The browser's counterpart of the TUI dashboard's loop: crawls the sites again and
 * again, {@code interval} apart, until {@link #stop()}, feeding a {@link Monitor}.
 * Every pass starts from each of the sites, not only the first.
 * What the monitor records (new problems, recoveries, pass totals) also goes to the
 * browser console, with a warning for each page whose links cannot be read.
 */
final class Session {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final List<URI> sites;
    private final Crawler.Options options;
    private final Duration interval;
    private final Monitor monitor;
    private final AsyncCrawler.Transport transport;
    private AsyncCrawler current;
    private AsyncCrawler lastCompleted;
    private int pass;
    private long nextPassAtNanos;
    private boolean stopped;
    private String finalReport = "";
    /** The newest monitor event already written to the console. */
    private Monitor.Event lastLogged;
    /** Pages already reported as unreadable, so each is warned about once. */
    private final Set<URI> unreadableLogged = new HashSet<>();

    Session(List<URI> sites, Crawler.Options options, Duration interval, Duration slowThreshold) {
        this.sites = sites;
        this.options = options;
        this.interval = interval;
        this.monitor = new Monitor(slowThreshold);
        this.transport = new BrowserTransport(options.timeout());
    }

    void start() {
        runPass();
    }

    private void runPass() {
        if (stopped) {
            return;
        }
        nextPassAtNanos = 0;
        int number = ++pass;
        AsyncCrawler crawler = new AsyncCrawler(sites, options, listener(number), transport);
        current = crawler;
        monitor.passStarted(number);
        try {
            crawler.start(sites, () -> passDone(crawler, number));
        } catch (RuntimeException e) {
            monitor.passFailed(number, e);
            crawler.cancel();
        }
    }

    /** The monitor, plus a console warning the first time a page's links turn out to be unreadable. */
    private CrawlListener listener(int number) {
        return new CrawlListener() {
            @Override
            public void onCheckStarted(URI url, boolean internal) {
                monitor.onCheckStarted(url, internal);
            }

            @Override
            public void onResult(LinkResult r) {
                monitor.onResult(r);
                if (r.internal() && AsyncCrawler.STATUS_HIDDEN.equals(r.detail()) && unreadableLogged.add(r.url())) {
                    Browser.log("warn", "pass #" + number + ": " + r.url() + " answered without CORS headers"
                            + " (no Access-Control-Allow-Origin), so the browser does not let uplink read it: its links"
                            + " are not crawled" + (r.referrer() == null ? "" : " (linked from " + r.referrer() + ")")
                            + ". To crawl it from a browser, the site must send Access-Control-Allow-Origin on it.");
                }
            }
        };
    }

    private void passDone(AsyncCrawler crawler, int number) {
        if (stopped || crawler != current) {
            return;
        }
        if (!crawler.isCancelled()) {
            monitor.passFinished(number, crawler.results(), crawler.stats());
            lastCompleted = crawler;
            publishReport(crawler, number);
            long unreadable = crawler.results().stream()
                    .filter(r -> r.internal() && AsyncCrawler.STATUS_HIDDEN.equals(r.detail()))
                    .count();
            if (unreadable > 0) {
                Browser.log("warn", "pass #" + number + ": " + unreadable + " page(s) on the crawled sites answered"
                        + " without CORS headers, so their links were not crawled; the uplink CLI would crawl them.");
            }
        }
        flushLog();
        nextPassAtNanos = System.nanoTime() + interval.toNanos();
        transport.schedule(interval.toMillis(), this::runPass);
    }

    /** Hands app.js the text report of a completed pass, for the Reports tab. */
    private void publishReport(AsyncCrawler crawler, int number) {
        LocalDateTime now = LocalDateTime.now();
        String text = PassReport.text(number, sites, crawler.stats(), crawler.results(),
                url -> new PassReport.FoundOn(crawler.foundOn(url), crawler.foundOnCount(url)),
                monitor.slowThreshold(), now);
        Monitor.PassSummary summary = monitor.lastPass();
        Browser.passReport(new Json().object()
                .put("pass", number)
                .put("finishedAt", now.format(WHEN))
                .put("sites", String.join(", ", sites.stream().map(URI::toString).toList()))
                .put("good", summary.stats().ok())
                .put("broken", summary.stats().broken())
                .put("unverified", summary.stats().blocked())
                .put("slow", summary.slow())
                .put("text", text)
                .end().toString());
    }

    /** Stops after the current request; the report of the last completed pass is kept, as the TUI prints it. */
    void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        nextPassAtNanos = 0;
        if (current != null) {
            current.cancel();
        }
        AsyncCrawler report = lastCompleted != null ? lastCompleted : current;
        Monitor.PassSummary last = monitor.lastPass();
        StringBuilder text = new StringBuilder(last == null
                ? "uplink stopped during the first pass; partial report:"
                : "uplink stopped after " + last.number() + " completed pass(es); report of pass #" + last.number() + ":")
                .append('\n');
        if (sites.size() > 1) {
            text.append("Addresses checked: ").append(String.join(", ", sites.stream().map(URI::toString).toList()))
                    .append('\n');
        }
        if (report != null) {
            text.append(Report.totals(report.root(), report.stats(), report.results(), report.isCancelled()));
        }
        finalReport = text.toString();
        flushLog();
        Browser.log("info", "stopped. " + finalReport);
    }

    /** Writes the monitor events recorded since the last call to the console. */
    void flushLog() {
        List<Monitor.Event> events = monitor.events();
        int from = 0;
        if (lastLogged != null) {
            for (int i = events.size() - 1; i >= 0; i--) {
                if (events.get(i) == lastLogged) {
                    from = i + 1;
                    break;
                }
            }
        }
        for (Monitor.Event e : events.subList(from, events.size())) {
            String level = switch (e.kind()) {
                case BROKEN, ERROR -> "warn";
                case UNVERIFIED, GONE, SLOW, RECOVERED, PASS -> "info";
            };
            Browser.log(level, e.kind() + " " + e.message());
        }
        if (!events.isEmpty()) {
            lastLogged = events.getLast();
        }
    }

    URI root() {
        return sites.getFirst();
    }

    List<URI> sites() {
        return sites;
    }

    Monitor monitor() {
        return monitor;
    }

    AsyncCrawler current() {
        return current;
    }

    AsyncCrawler lastCompleted() {
        return lastCompleted;
    }

    int pass() {
        return pass;
    }

    boolean stopped() {
        return stopped;
    }

    Duration interval() {
        return interval;
    }

    /** When the next pass starts, or 0 while one runs. */
    long nextPassAtNanos() {
        return nextPassAtNanos;
    }

    String finalReport() {
        return finalReport;
    }
}
