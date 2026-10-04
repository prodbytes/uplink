package org.uplink.report;

import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import org.uplink.crawl.CrawlStats;
import org.uplink.crawl.LinkResult;

/** Plain-text formatting shared by the console and TUI front ends. */
public final class Report {

    private Report() {
    }

    /** One-line progress summary. */
    public static String progressLine(CrawlStats s) {
        return String.format("checked %d | good %d | broken %d | unverified %d | in flight %d | queued %d | pages %d | %s",
                s.checked(), s.ok(), s.broken(), s.blocked(), s.inFlight(), s.queued(), s.pages(),
                duration(s.elapsed()));
    }

    /** Bad links first by outcome (broken before unverified), then by URL. */
    public static List<LinkResult> badLinks(List<LinkResult> results) {
        return results.stream()
                .filter(LinkResult::isBad)
                .sorted(Comparator.comparing(LinkResult::outcome).thenComparing(r -> r.url().toString()))
                .toList();
    }

    /** The end-of-run totals report. */
    public static String totals(URI root, CrawlStats s, List<LinkResult> results, boolean cancelled) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append("uplink report for ").append(root).append('\n');
        sb.append(cancelled ? "Cancelled after " : "Finished in ").append(duration(s.elapsed()))
                .append(" - ").append(s.checked()).append(" unique links checked, ")
                .append(s.pages()).append(" pages crawled").append('\n').append('\n');
        sb.append(String.format("  Good links:        %d%n", s.ok()));
        sb.append(String.format("  Broken links:      %d%n", s.broken()));
        sb.append(String.format("  Unverified links:  %d  (server refused automated access)%n", s.blocked()));

        List<LinkResult> bad = badLinks(results);
        appendSection(sb, "Broken links", bad.stream().filter(r -> r.outcome() == LinkResult.Outcome.BROKEN).toList());
        appendSection(sb, "Unverified links", bad.stream().filter(r -> r.outcome() == LinkResult.Outcome.BLOCKED).toList());
        if (bad.isEmpty()) {
            sb.append('\n').append("No bad links found.").append('\n');
        }
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String title, List<LinkResult> links) {
        if (links.isEmpty()) {
            return;
        }
        sb.append('\n').append(title).append(" (").append(links.size()).append("):").append('\n');
        for (LinkResult r : links) {
            sb.append("  ").append(describe(r)).append('\n');
            if (r.referrer() != null) {
                sb.append("      found on ").append(r.referrer()).append('\n');
            }
        }
    }

    /** {@code [404 Not Found] https://... (external)} */
    public static String describe(LinkResult r) {
        return "[" + (r.status() > 0 ? r.status() + " " : "") + r.detail() + "] " + r.url()
                + (r.internal() ? "" : " (external)");
    }

    public static String duration(Duration d) {
        long seconds = d.toSeconds();
        if (seconds < 60) {
            return String.format("%.1fs", d.toMillis() / 1000.0);
        }
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long sec = seconds % 60;
        return h > 0 ? String.format("%dh %02dm %02ds", h, m, sec) : String.format("%dm %02ds", m, sec);
    }
}
