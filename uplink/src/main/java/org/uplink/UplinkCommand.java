package org.uplink;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.uplink.crawl.Crawler;
import org.uplink.crawl.Links;
import org.uplink.report.ConsoleReporter;
import org.uplink.report.Dashboard;
import org.uplink.report.ReportLog;

import dev.tamboui.toolkit.app.ToolkitRunner;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@TopCommand
@Command(name = "uplink", mixinStandardHelpOptions = true, versionProvider = VersionProvider.class,
        description = {
                "Crawls a website and reports broken links.",
                "Pages on the same site are crawled recursively; links to other sites are checked but not followed.",
                "More sites to crawl can follow the URL, separated by , or ; (e.g. https://example.com,docs.example.com).",
                "On a desktop terminal it runs a monitoring dashboard that re-checks the site in a loop until Ctrl+C.",
                "In CI/CD it crawls once, logs to the console and exits non-zero if any link is broken.",
                "Either way, the final report is also saved to .uplink.local.log.txt in the current directory."
        },
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
                "0:no broken links",
                "1:broken links found",
                "2:invalid arguments or unexpected error"
        })
public class UplinkCommand implements Callable<Integer> {

    enum Mode { auto, tui, console }

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", paramLabel = "URL[,SITE...]",
            description = "http(s) URL to crawl, e.g. https://example.com, optionally followed by more sites"
                    + " (URLs or hosts, separated by , or ;) whose pages are crawled too")
    String url;

    @Option(names = "--mode", defaultValue = "auto",
            description = "Front end: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}, detects desktop vs CI/CD)")
    Mode mode;

    @Option(names = {"-c", "--concurrency"}, defaultValue = "8",
            description = "Concurrent requests per host (default: ${DEFAULT-VALUE})")
    int concurrency;

    @Option(names = "--max-in-flight", defaultValue = "64",
            description = "Concurrent requests across all hosts, each on a virtual thread (default: ${DEFAULT-VALUE})")
    int maxInFlight;

    @Option(names = {"-t", "--timeout"}, defaultValue = "20", description = "Per-request timeout in seconds (default: ${DEFAULT-VALUE})")
    int timeoutSeconds;

    @Option(names = "--max-pages", defaultValue = "10000", description = "Most pages to crawl for links (default: ${DEFAULT-VALUE})")
    int maxPages;

    @Option(names = "--summary-interval", defaultValue = "30",
            description = "Seconds between progress summaries in console mode (default: ${DEFAULT-VALUE})")
    int summaryIntervalSeconds;

    @Option(names = "--interval", defaultValue = "30",
            description = "Seconds to wait between passes in the dashboard (default: ${DEFAULT-VALUE})")
    int intervalSeconds;

    @Option(names = "--slow", defaultValue = "1000",
            description = "Response time in milliseconds from which a link counts as slow (default: ${DEFAULT-VALUE})")
    int slowMillis;

    @Option(names = "--follow-redirects", negatable = true, fallbackValue = "true",
            defaultValue = "${env:UPLINK_FOLLOW_REDIRECTS:-true}",
            description = "Follow redirects and check where they lead; with --no-follow-redirects a 3xx counts as good"
                    + " (default: ${DEFAULT-VALUE}, from UPLINK_FOLLOW_REDIRECTS when set)")
    boolean followRedirects;

    @Option(names = "--sitemaps", negatable = true, fallbackValue = "true", defaultValue = "true",
            description = "Also crawl the pages listed in the sitemaps each site declares in its robots.txt, which"
                    + " finds pages no HTML links to (default: ${DEFAULT-VALUE})")
    boolean sitemaps;

    @Override
    public Integer call() throws Exception {
        PrintStream out = System.out;
        Optional<List<URI>> sites = Links.parseSites(url);
        if (sites.isEmpty()) {
            if (url != null) {
                System.err.println("uplink: the first argument must be an http(s) URL, optionally followed by"
                        + " more sites separated by , or ;, got: " + url);
            }
            spec.commandLine().usage(System.err);
            return 2;
        }
        if (concurrency < 1 || maxInFlight < 1 || timeoutSeconds < 1 || maxPages < 1 || summaryIntervalSeconds < 1
                || intervalSeconds < 0 || slowMillis < 1) {
            System.err.println("uplink: numeric options must be positive");
            return 2;
        }

        RunEnvironment env = RunEnvironment.detect();
        boolean tui = switch (mode) {
            case tui -> true;
            case console -> false;
            case auto -> env.isDesktop();
        };
        String reason = mode == Mode.auto ? env.reason() : "--mode=" + mode;

        Crawler.Options options = new Crawler.Options(concurrency, maxInFlight, Duration.ofSeconds(timeoutSeconds),
                maxPages, Crawler.Options.DEFAULT_USER_AGENT, followRedirects, sitemaps);
        ToolkitRunner terminal = null;
        if (tui) {
            try {
                terminal = Dashboard.openTerminal();
            } catch (Exception e) {
                System.err.println("uplink: cannot start the terminal UI (" + e.getMessage() + "), using console output");
                reason = "terminal UI unavailable";
            }
        }
        if (terminal != null) {
            Dashboard dashboard = new Dashboard(sites.get(), options, Duration.ofSeconds(intervalSeconds),
                    Duration.ofMillis(slowMillis));
            boolean broken = dashboard.run(terminal, out);
            saveLog(dashboard.finalReport());
            return broken ? 1 : 0;
        }
        // CI/CD: a single pass; the exit code fails the pipeline when a link is broken.
        ConsoleReporter reporter = new ConsoleReporter(out, Duration.ofSeconds(summaryIntervalSeconds));
        Crawler crawler = new Crawler(sites.get(), options, reporter);
        saveLog(reporter.run(crawler, reason));
        return crawler.stats().broken() > 0 ? 1 : 0;
    }

    /** A log that cannot be written is worth a warning, not a failed check. */
    private static void saveLog(String report) {
        try {
            ReportLog.save(ReportLog.DEFAULT, report);
        } catch (IOException e) {
            System.err.println("uplink: cannot save the report to " + ReportLog.DEFAULT + ": " + e.getMessage());
        }
    }
}
