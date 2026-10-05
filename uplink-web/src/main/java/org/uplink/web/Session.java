package org.uplink.web;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.uplink.crawl.AsyncCrawler;
import org.uplink.crawl.Crawler;
import org.uplink.report.Monitor;
import org.uplink.report.Report;

/**
 * The browser's counterpart of the TUI dashboard's loop: crawls the sites again and
 * again, {@code interval} apart, until {@link #stop()}, feeding a {@link Monitor}.
 */
final class Session {

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
        AsyncCrawler crawler = new AsyncCrawler(sites, options, monitor, transport);
        current = crawler;
        monitor.passStarted(number);
        try {
            crawler.start(() -> passDone(crawler, number));
        } catch (RuntimeException e) {
            monitor.passFailed(number, e);
            crawler.cancel();
        }
    }

    private void passDone(AsyncCrawler crawler, int number) {
        if (stopped || crawler != current) {
            return;
        }
        if (!crawler.isCancelled()) {
            monitor.passFinished(number, crawler.results(), crawler.stats());
            lastCompleted = crawler;
        }
        nextPassAtNanos = System.nanoTime() + interval.toNanos();
        transport.schedule(interval.toMillis(), this::runPass);
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
        if (report != null) {
            text.append(Report.totals(report.root(), report.stats(), report.results(), report.isCancelled()));
        }
        finalReport = text.toString();
    }

    URI root() {
        return sites.getFirst();
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
