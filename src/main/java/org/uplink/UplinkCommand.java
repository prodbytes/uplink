package org.uplink;

import java.io.PrintStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.uplink.crawl.Crawler;
import org.uplink.crawl.Links;
import org.uplink.report.ConsoleReporter;
import org.uplink.report.Dashboard;

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
                "On a desktop terminal it runs a monitoring dashboard that re-checks the site in a loop until Ctrl+C.",
                "In CI/CD it crawls once, logs to the console and exits non-zero if any link is broken."
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

    @Parameters(index = "0", arity = "0..1", paramLabel = "URL", description = "http(s) URL to crawl, e.g. https://example.com")
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

    @Override
    public Integer call() throws Exception {
        PrintStream out = System.out;
        Optional<URI> start = Links.parseStartUrl(url);
        if (start.isEmpty()) {
            if (url != null) {
                System.err.println("uplink: the first argument must be an http(s) URL, got: " + url);
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
                maxPages, Crawler.Options.DEFAULT_USER_AGENT);
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
            Dashboard dashboard = new Dashboard(start.get(), options, Duration.ofSeconds(intervalSeconds),
                    Duration.ofMillis(slowMillis));
            return dashboard.run(terminal, out) ? 1 : 0;
        }
        // CI/CD: a single pass; the exit code fails the pipeline when a link is broken.
        ConsoleReporter reporter = new ConsoleReporter(out, Duration.ofSeconds(summaryIntervalSeconds));
        Crawler crawler = new Crawler(start.get(), options, reporter);
        reporter.run(crawler, reason);
        return crawler.stats().broken() > 0 ? 1 : 0;
    }
}
