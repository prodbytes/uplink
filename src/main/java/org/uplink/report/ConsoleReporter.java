package org.uplink.report;

import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.uplink.crawl.CrawlListener;
import org.uplink.crawl.Crawler;
import org.uplink.crawl.LinkResult;

/**
 * Non-interactive front end for CI/CD: plain log lines, a bad link as soon as it is
 * found, a progress summary at a fixed interval, and the totals report at the end.
 */
public final class ConsoleReporter implements CrawlListener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final PrintStream out;
    private final Duration summaryInterval;

    public ConsoleReporter(PrintStream out, Duration summaryInterval) {
        this.out = out;
        this.summaryInterval = summaryInterval;
    }

    @Override
    public void onResult(LinkResult result) {
        if (result.isBad()) {
            log((result.outcome() == LinkResult.Outcome.BROKEN ? "BROKEN     " : "UNVERIFIED ") + Report.describe(result)
                    + (result.referrer() != null ? " <- " + result.referrer() : ""));
        }
    }

    /** Runs the crawl to completion, printing periodic summaries and the final report. */
    public void run(Crawler crawler, String environmentReason) throws InterruptedException {
        log("uplink: checking " + crawler.root() + " (CI mode: " + environmentReason + ")");
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("uplink-summary").factory());
        long period = summaryInterval.toMillis();
        ticker.scheduleAtFixedRate(() -> log("progress: " + Report.progressLine(crawler.stats())),
                period, period, TimeUnit.MILLISECONDS);
        try {
            crawler.run();
        } finally {
            ticker.shutdownNow();
        }
        log("done: " + Report.progressLine(crawler.stats()));
        out.print(Report.totals(crawler.root(), crawler.stats(), crawler.results(), crawler.isCancelled()));
        out.flush();
    }

    private void log(String message) {
        synchronized (out) {
            out.println(LocalTime.now().format(TIME) + " " + message);
            out.flush();
        }
    }
}
