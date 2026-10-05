package org.uplink.report;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import org.uplink.crawl.AsyncCrawler;
import org.uplink.crawl.CrawlStats;
import org.uplink.crawl.LinkResult;

/**
 * The plain-text report of one crawl pass: totals, then every broken and unverified
 * link (on the crawled sites or not) with the pages it was found on, then the slow ones.
 */
public final class PassReport {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** The pages a link was found on, at most a few of them, and how many there were. */
    public record FoundOn(List<URI> pages, int count) {
    }

    private PassReport() {
    }

    /**
     * @param foundOn the pages each link was found on, such as {@link AsyncCrawler#foundOn(URI)}
     */
    public static String text(int pass, List<URI> sites, CrawlStats stats, List<LinkResult> results,
            Function<URI, FoundOn> foundOn, Duration slowThreshold, LocalDateTime finishedAt) {
        List<LinkResult> bad = Report.badLinks(results);
        List<LinkResult> broken = bad.stream().filter(r -> r.outcome() == LinkResult.Outcome.BROKEN).toList();
        List<LinkResult> unverified = bad.stream().filter(r -> r.outcome() == LinkResult.Outcome.BLOCKED).toList();
        List<LinkResult> slow = results.stream()
                .filter(r -> !r.isBad() && r.elapsed().compareTo(slowThreshold) >= 0)
                .sorted(Comparator.comparing(LinkResult::elapsed).reversed().thenComparing(r -> r.url().toString()))
                .toList();
        long internal = results.stream().filter(LinkResult::internal).count();
        long hidden = results.stream().filter(r -> AsyncCrawler.STATUS_HIDDEN.equals(r.detail())).count();
        long unreadable = results.stream()
                .filter(r -> r.internal() && AsyncCrawler.STATUS_HIDDEN.equals(r.detail())).count();
        Map<String, Integer> byCode = new TreeMap<>();
        bad.forEach(r -> byCode.merge(r.statusLabel(), 1, Integer::sum));

        StringBuilder sb = new StringBuilder();
        sb.append("uplink report, pass #").append(pass).append('\n');
        sb.append(sites.size() == 1 ? "Address:   " : "Addresses: ")
                .append(String.join(", ", sites.stream().map(URI::toString).toList())).append('\n');
        sb.append("Finished:  ").append(finishedAt.format(WHEN))
                .append(" (took ").append(Report.duration(stats.elapsed())).append(")\n");

        sb.append("\nSummary\n");
        line(sb, "Links checked", results.size() + "  (" + internal + " on the sites, "
                + (results.size() - internal) + " on other sites)");
        line(sb, "Pages crawled", String.valueOf(stats.pages()));
        line(sb, "Requests sent", String.valueOf(stats.requests()));
        line(sb, "Good", (results.size() - bad.size())
                + (hidden > 0 ? "  (" + hidden + " answered with their status hidden (no CORS), counted as good)" : ""));
        line(sb, "Broken", String.valueOf(broken.size()));
        line(sb, "Unverified", unverified.size() + (unverified.isEmpty() ? ""
                : "  (refused automated or cross-origin access; may be fine)"));
        line(sb, "Slow (>= " + slowThreshold.toMillis() + "ms)", String.valueOf(slow.size()));
        if (!byCode.isEmpty()) {
            line(sb, "Bad by status", String.join(", ",
                    byCode.entrySet().stream().map(e -> e.getValue() + " x " + e.getKey()).toList()));
        }
        if (unreadable > 0) {
            line(sb, "Unreadable pages", unreadable + "  (no CORS headers, so their links were not crawled)");
        }

        section(sb, "Broken links", broken, foundOn);
        section(sb, "Unverified links", unverified, foundOn);
        if (bad.isEmpty()) {
            sb.append("\nNo broken or unverified links.\n");
        }
        if (!slow.isEmpty()) {
            sb.append("\nSlow links (").append(slow.size()).append("), slowest first:\n");
            for (LinkResult r : slow) {
                sb.append(String.format("  %6dms  %s%s%n", r.elapsed().toMillis(), r.url(),
                        r.internal() ? "" : " (external)"));
            }
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, String value) {
        sb.append(String.format("  %-18s %s%n", label + ":", value));
    }

    private static void section(StringBuilder sb, String title, List<LinkResult> links, Function<URI, FoundOn> foundOn) {
        if (links.isEmpty()) {
            return;
        }
        sb.append('\n').append(title).append(" (").append(links.size()).append("):\n");
        for (LinkResult r : links) {
            sb.append("  ").append(Report.describe(r)).append('\n');
            FoundOn on = foundOn.apply(r.url());
            if (on.count() == 0) {
                sb.append("      start URL\n");
                continue;
            }
            sb.append("      found on ").append(on.count()).append(on.count() == 1 ? " page" : " pages").append(":\n");
            on.pages().forEach(page -> sb.append("        ").append(page).append('\n'));
            if (on.count() > on.pages().size()) {
                sb.append("        ... and ").append(on.count() - on.pages().size()).append(" more\n");
            }
        }
    }
}
