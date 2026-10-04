package org.uplink.report;

import java.net.URI;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import org.uplink.crawl.CrawlListener;
import org.uplink.crawl.CrawlStats;
import org.uplink.crawl.Crawler;
import org.uplink.crawl.LinkResult;

/**
 * State behind the continuous-monitoring dashboard: which links are currently bad or
 * slow, and an event log of what changed between crawl passes. Thread-safe: crawler
 * threads write, the render thread reads.
 */
public final class Monitor implements CrawlListener {

    public enum Kind { PASS, BROKEN, UNVERIFIED, RECOVERED, SLOW, GONE, ERROR }

    public record Event(LocalTime time, Kind kind, String message) {
    }

    /** One bar of the latency histogram: pages that took less than {@code upperMillis}. */
    public record Bucket(String label, long upperMillis, int count) {
    }

    /** Upper bounds of the histogram buckets; the last bucket is open-ended. */
    private static final long[] BUCKET_BOUNDS = {100, 250, 500, 1000, 2000, 5000, Long.MAX_VALUE};

    /** Totals of a completed pass. */
    public record PassSummary(int number, CrawlStats stats, int slow, int newlyBad, int recovered, LocalTime finishedAt) {
    }

    private static final int MAX_EVENTS = 1000;

    private final Duration slowThreshold;
    private final Map<URI, LinkResult> bad = new ConcurrentHashMap<>();
    private final Map<URI, LinkResult> slow = new ConcurrentHashMap<>();
    private final Map<URI, LinkResult> pages = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Event> events = new ConcurrentLinkedDeque<>();
    private final AtomicInteger eventCount = new AtomicInteger();
    private final AtomicInteger newlyBad = new AtomicInteger();
    private final AtomicInteger recovered = new AtomicInteger();
    private volatile URI lastStarted;
    private volatile PassSummary lastPass;

    public Monitor(Duration slowThreshold) {
        this.slowThreshold = slowThreshold;
    }

    public Duration slowThreshold() {
        return slowThreshold;
    }

    public void passStarted(int number) {
        newlyBad.set(0);
        recovered.set(0);
        event(Kind.PASS, "Pass #" + number + " started");
    }

    /**
     * Closes a pass: links that are no longer linked from the site drop out of the bad
     * and slow lists, and the pass totals are recorded.
     */
    public void passFinished(int number, Crawler crawler) {
        Set<URI> checked = new HashSet<>();
        for (LinkResult r : crawler.results()) {
            checked.add(r.url());
        }
        for (URI url : List.copyOf(bad.keySet())) {
            if (!checked.contains(url) && bad.remove(url) != null) {
                event(Kind.GONE, url + " is no longer linked from the site");
            }
        }
        slow.keySet().retainAll(checked);
        pages.keySet().retainAll(checked);

        CrawlStats s = crawler.stats();
        lastPass = new PassSummary(number, s, slow.size(), newlyBad.get(), recovered.get(), LocalTime.now());
        event(Kind.PASS, String.format("Pass #%d finished in %s: %d pages, %d requests, %d good, %d broken, %d unverified, %d slow (%d new problems, %d recovered)",
                number, Report.duration(s.elapsed()), s.pages(), s.requests(), s.ok(), s.broken(), s.blocked(), slow.size(),
                newlyBad.get(), recovered.get()));
    }

    public void passFailed(int number, Exception e) {
        event(Kind.ERROR, "Pass #" + number + " failed: " + e);
    }

    @Override
    public void onCheckStarted(URI url, boolean internal) {
        lastStarted = url;
    }

    @Override
    public void onResult(LinkResult r) {
        if (r.internal()) {
            pages.put(r.url(), r);
        }
        if (r.isBad()) {
            LinkResult previous = bad.put(r.url(), r);
            if (previous == null) {
                newlyBad.incrementAndGet();
                event(r.outcome() == LinkResult.Outcome.BROKEN ? Kind.BROKEN : Kind.UNVERIFIED, describe(r));
            } else if (previous.status() != r.status() || previous.outcome() != r.outcome()) {
                event(r.outcome() == LinkResult.Outcome.BROKEN ? Kind.BROKEN : Kind.UNVERIFIED,
                        describe(r) + " (was " + previous.statusLabel() + " " + previous.detail() + ")");
            }
            slow.remove(r.url());
            return;
        }
        LinkResult wasBad = bad.remove(r.url());
        if (wasBad != null) {
            recovered.incrementAndGet();
            event(Kind.RECOVERED, r.url() + " is back (" + r.status() + ", was " + wasBad.statusLabel() + " " + wasBad.detail() + ")");
        }
        if (r.elapsed().compareTo(slowThreshold) >= 0) {
            if (slow.put(r.url(), r) == null) {
                event(Kind.SLOW, r.url() + " took " + r.elapsed().toMillis() + "ms");
            }
        } else {
            slow.remove(r.url());
        }
    }

    private static String describe(LinkResult r) {
        return Report.describe(r) + (r.referrer() != null ? " on " + r.referrer() : "");
    }

    public void event(Kind kind, String message) {
        events.addLast(new Event(LocalTime.now(), kind, message));
        if (eventCount.incrementAndGet() > MAX_EVENTS && events.pollFirst() != null) {
            eventCount.decrementAndGet();
        }
    }

    /** Currently bad links, broken before unverified, then by URL. */
    public List<LinkResult> badLinks() {
        return Report.badLinks(new ArrayList<>(bad.values()));
    }

    /** Currently slow links, slowest first. */
    public List<LinkResult> slowLinks() {
        return slow.values().stream()
                .sorted(Comparator.comparing(LinkResult::elapsed).reversed())
                .toList();
    }

    /** Latest result of every page on the site, slowest first. */
    public List<LinkResult> pageLatencies() {
        return pages.values().stream()
                .sorted(Comparator.comparing(LinkResult::elapsed).reversed().thenComparing(LinkResult::url))
                .toList();
    }

    /** Page latencies grouped into fixed buckets, fastest first; empty buckets included. */
    public static List<Bucket> histogram(List<LinkResult> results) {
        int[] counts = new int[BUCKET_BOUNDS.length];
        for (LinkResult r : results) {
            long millis = r.elapsed().toMillis();
            int i = 0;
            while (millis >= BUCKET_BOUNDS[i]) {
                i++;
            }
            counts[i]++;
        }
        List<Bucket> buckets = new ArrayList<>(counts.length);
        for (int i = 0; i < counts.length; i++) {
            long upper = BUCKET_BOUNDS[i];
            String label = upper == Long.MAX_VALUE
                    ? "≥" + seconds(BUCKET_BOUNDS[i - 1])
                    : "<" + (upper < 1000 ? upper + "ms" : seconds(upper));
            buckets.add(new Bucket(label, upper, counts[i]));
        }
        return buckets;
    }

    private static String seconds(long millis) {
        return millis % 1000 == 0 ? millis / 1000 + "s" : millis + "ms";
    }

    /** Events, oldest first. */
    public List<Event> events() {
        return List.copyOf(events);
    }

    public URI lastStarted() {
        return lastStarted;
    }

    /** The most recent completed pass, or {@code null} before the first one ends. */
    public PassSummary lastPass() {
        return lastPass;
    }
}
