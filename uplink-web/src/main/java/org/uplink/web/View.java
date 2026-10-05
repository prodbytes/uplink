package org.uplink.web;

import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.uplink.crawl.AsyncCrawler;
import org.uplink.crawl.CrawlStats;
import org.uplink.crawl.LinkResult;
import org.uplink.report.Monitor;
import org.uplink.report.Report;

/**
 * The state behind the page, as JSON for app.js: the same panels as the TUI dashboard
 * (header, progress, broken, slow, events and latency) plus the uptime of each address, computed by uplink's
 * {@link Monitor} and {@link Report}.
 */
final class View {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Most rows sent per list; the rest are counted. The page redraws several times a second. */
    private static final int MAX_ROWS = 2000;
    /** Most recent events sent. */
    private static final int MAX_EVENTS = 300;
    /** Most recent checks of each address drawn on the uptime timeline; uptime counts all the monitor keeps. */
    private static final int MAX_TIMELINE = 120;
    /** Unreadable pages named in the notice; the console lists them all. */
    private static final int MAX_HIDDEN_EXAMPLES = 8;

    private View() {
    }

    static String idle(String error) {
        return new Json().object()
                .put("running", false)
                .put("error", error)
                .end().toString();
    }

    static String of(Session session, String error) {
        Monitor monitor = session.monitor();
        AsyncCrawler crawler = session.current();
        long next = session.nextPassAtNanos();
        boolean crawling = !session.stopped() && next == 0;

        Json json = new Json().object()
                .put("running", !session.stopped())
                .put("error", error)
                .put("root", session.root().toString())
                .put("siteCount", session.sites().size())
                .put("pass", session.pass())
                .put("phase", session.stopped() ? "stopped" : crawling ? "crawling" : "waiting")
                .put("state", state(session))
                .put("slowMillis", monitor.slowThreshold().toMillis());

        // Current pass, like the header's second line and the progress gauge.
        if (crawler != null && crawling) {
            CrawlStats s = crawler.stats();
            URI checking = monitor.lastStarted();
            json.object("now")
                    .put("checked", s.checked())
                    .put("discovered", s.discovered())
                    .put("inFlight", s.inFlight())
                    .put("queued", s.queued())
                    .put("pages", s.pages())
                    .put("requests", s.requests())
                    .put("elapsed", Report.duration(s.elapsed()))
                    .put("checking", checking == null ? "" : checking.toString())
                    .end();
            json.object("progress")
                    .put("ratio", Math.min(1.0, (double) s.checked() / Math.max(1, s.discovered())))
                    .put("label", s.checked() + " / " + s.discovered() + " links")
                    .end();
        } else if (!session.stopped()) {
            long total = Math.max(1, session.interval().toNanos());
            long left = Math.max(0, next - System.nanoTime());
            json.putNull("now");
            json.object("progress")
                    .put("ratio", next == 0 ? 0.0 : 1.0 - (double) left / total)
                    .put("label", "next pass")
                    .end();
        } else {
            json.putNull("now").putNull("progress");
        }

        Monitor.PassSummary last = monitor.lastPass();
        if (last == null) {
            json.putNull("last");
        } else {
            CrawlStats s = last.stats();
            json.object("last")
                    .put("number", last.number())
                    .put("ok", s.ok())
                    .put("broken", s.broken())
                    .put("blocked", s.blocked())
                    .put("slow", last.slow())
                    .put("pages", s.pages())
                    .put("requests", s.requests())
                    .put("took", Report.duration(s.elapsed()))
                    .put("finishedAt", last.finishedAt().format(TIME))
                    .end();
        }

        // Links answered without CORS: reachable, but their status (and pages' links) are unreadable.
        AsyncCrawler counted = crawling || session.lastCompleted() == null ? crawler : session.lastCompleted();
        long hidden = 0;
        List<String> hiddenPages = new ArrayList<>();
        if (counted != null) {
            for (LinkResult r : counted.results()) {
                if (AsyncCrawler.STATUS_HIDDEN.equals(r.detail())) {
                    hidden++;
                    if (r.internal()) {
                        hiddenPages.add(r.url().toString());
                    }
                }
            }
        }
        hiddenPages.sort(null);
        json.put("hidden", hidden).put("hiddenPages", hiddenPages.size());
        json.array("hiddenExamples");
        for (String url : hiddenPages.subList(0, Math.min(hiddenPages.size(), MAX_HIDDEN_EXAMPLES))) {
            json.object().put("url", url).end();
        }
        json.endArray();

        List<LinkResult> bad = monitor.badLinks();
        Map<String, Integer> byCode = new TreeMap<>();
        long broken = 0;
        for (LinkResult r : bad) {
            byCode.merge(r.statusLabel(), 1, Integer::sum);
            broken += r.outcome() == LinkResult.Outcome.BROKEN ? 1 : 0;
        }
        json.put("brokenCount", broken).put("unverifiedCount", bad.size() - broken).put("badTotal", bad.size());
        json.array("codes");
        byCode.forEach((code, n) -> json.object().put("code", code).put("count", n).end());
        json.endArray();
        json.array("bad");
        for (LinkResult r : bad.subList(0, Math.min(bad.size(), MAX_ROWS))) {
            json.object()
                    .put("status", r.statusLabel())
                    .put("broken", r.outcome() == LinkResult.Outcome.BROKEN)
                    .put("url", r.url().toString())
                    .put("detail", r.detail())
                    .put("internal", r.internal())
                    .put("referrer", r.referrer() == null ? null : r.referrer().toString())
                    .end();
        }
        json.endArray();

        List<LinkResult> slow = monitor.slowLinks();
        json.put("slowTotal", slow.size());
        json.array("slow");
        for (LinkResult r : slow.subList(0, Math.min(slow.size(), MAX_ROWS))) {
            json.object()
                    .put("millis", r.elapsed().toMillis())
                    .put("internal", r.internal())
                    .put("url", r.url().toString())
                    .end();
        }
        json.endArray();

        List<LinkResult> pages = monitor.pageLatencies();
        json.put("pagesTotal", pages.size());
        if (!pages.isEmpty()) {
            json.object("latency")
                    .put("p50", percentile(pages, 50))
                    .put("p95", percentile(pages, 95))
                    .put("max", pages.getFirst().elapsed().toMillis())
                    .end();
        } else {
            json.putNull("latency");
        }
        json.array("pages");
        for (LinkResult r : pages.subList(0, Math.min(pages.size(), MAX_ROWS))) {
            json.object()
                    .put("millis", r.elapsed().toMillis())
                    .put("status", statusLabel(r))
                    .put("bad", r.isBad())
                    .put("url", r.url().toString())
                    .end();
        }
        json.endArray();
        json.array("histogram");
        for (Monitor.Bucket b : Monitor.histogram(pages)) {
            json.object()
                    .put("label", b.label())
                    .put("count", b.count())
                    .put("slow", b.upperMillis() > monitor.slowThreshold().toMillis())
                    .end();
        }
        json.endArray();

        List<Monitor.Event> events = monitor.events();
        json.array("events");
        for (Monitor.Event e : events.subList(Math.max(0, events.size() - MAX_EVENTS), events.size())) {
            json.object()
                    .put("time", e.time().format(TIME))
                    .put("kind", e.kind().name())
                    .put("message", e.message())
                    .end();
        }
        json.endArray();

        json.array("uptime");
        monitor.uptime().forEach((url, checks) -> uptime(json, url, checks));
        json.endArray();

        json.array("sites");
        for (URI site : session.sites()) {
            json.object().put("url", site.toString()).end();
        }
        json.endArray();

        json.put("report", session.stopped() ? session.finalReport() : null);
        return json.end().toString();
    }

    /**
     * One address on the uptime tab. A check is up, down (broken) or unverified (refused, or
     * no answer the browser lets through); uptime is the share of up checks among the
     * verified ones, or null when none was.
     */
    private static void uptime(Json json, URI url, List<Monitor.Check> checks) {
        long up = 0;
        long down = 0;
        for (Monitor.Check c : checks) {
            up += c.result().outcome() == LinkResult.Outcome.OK ? 1 : 0;
            down += c.result().outcome() == LinkResult.Outcome.BROKEN ? 1 : 0;
        }
        LinkResult last = checks.getLast().result();
        json.object()
                .put("url", url.toString())
                .put("checks", checks.size())
                .put("up", up)
                .put("down", down)
                .put("unverified", checks.size() - up - down);
        if (up + down > 0) {
            json.put("ratio", (double) up / (up + down));
        } else {
            json.putNull("ratio");
        }
        json.put("state", checkState(last))
                .put("status", statusLabel(last))
                .put("millis", last.elapsed().toMillis())
                .put("detail", last.detail());
        json.array("timeline");
        for (Monitor.Check c : checks.subList(Math.max(0, checks.size() - MAX_TIMELINE), checks.size())) {
            LinkResult r = c.result();
            json.object()
                    .put("pass", c.pass())
                    .put("time", c.time().format(TIME))
                    .put("state", checkState(r))
                    .put("status", statusLabel(r))
                    .put("millis", r.elapsed().toMillis())
                    .put("detail", r.detail())
                    .end();
        }
        json.endArray().end();
    }

    private static String checkState(LinkResult r) {
        return switch (r.outcome()) {
            case OK -> "up";
            case BROKEN -> "down";
            case BLOCKED -> "unverified";
        };
    }

    private static String state(Session session) {
        if (session.stopped()) {
            return "stopped after pass #" + session.pass();
        }
        long next = session.nextPassAtNanos();
        if (next != 0) {
            long seconds = Math.max(0, (next - System.nanoTime()) / 1_000_000_000L);
            return "pass #" + session.pass() + " done, next pass in " + seconds + "s";
        }
        return "pass #" + session.pass() + " running";
    }

    /** The status column: like the TUI's, but "—" for a link that answered with its status hidden, not "ERR". */
    private static String statusLabel(LinkResult r) {
        return AsyncCrawler.STATUS_HIDDEN.equals(r.detail()) ? "—" : r.statusLabel();
    }

    /** Nearest-rank percentile of latencies sorted slowest first, as in the TUI. */
    private static long percentile(List<LinkResult> slowestFirst, int p) {
        int rank = (int) Math.ceil(p / 100.0 * slowestFirst.size());
        return slowestFirst.get(slowestFirst.size() - Math.max(1, rank)).elapsed().toMillis();
    }
}
