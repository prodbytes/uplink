package org.uplink.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.uplink.TestSite;
import org.uplink.crawl.Crawler;
import org.uplink.crawl.LinkResult;
import org.uplink.crawl.LinkResult.Outcome;

class MonitorTest {

    private static final URI PAGE = URI.create("https://site.example/");
    private static final URI LINK = URI.create("https://site.example/docs");

    private static LinkResult result(URI url, int status, Outcome outcome, long millis) {
        return new LinkResult(url, PAGE, true, status, outcome, "detail", Duration.ofMillis(millis));
    }

    private static List<Monitor.Kind> kinds(Monitor monitor) {
        return monitor.events().stream().map(Monitor.Event::kind).toList();
    }

    @Test
    void tracksBrokenLinksAcrossPasses() {
        Monitor monitor = new Monitor(Duration.ofSeconds(1));
        monitor.onResult(result(LINK, 404, Outcome.BROKEN, 10));
        monitor.onResult(result(LINK, 404, Outcome.BROKEN, 10)); // same failure again: no new event
        assertEquals(List.of(Monitor.Kind.BROKEN), kinds(monitor));
        assertEquals(1, monitor.badLinks().size());

        monitor.onResult(result(LINK, 500, Outcome.BROKEN, 10)); // status changed
        assertEquals(List.of(Monitor.Kind.BROKEN, Monitor.Kind.BROKEN), kinds(monitor));
        assertTrue(monitor.events().getLast().message().contains("was 404"));

        monitor.onResult(result(LINK, 200, Outcome.OK, 10));
        assertEquals(Monitor.Kind.RECOVERED, monitor.events().getLast().kind());
        assertTrue(monitor.badLinks().isEmpty());
    }

    @Test
    void passEndDropsLinksNoLongerOnTheSite() throws Exception {
        try (TestSite site = new TestSite()) {
            Monitor monitor = new Monitor(Duration.ofSeconds(1));
            monitor.onResult(result(LINK, 404, Outcome.BROKEN, 10)); // broken in an earlier pass
            Crawler crawler = new Crawler(URI.create(site.externalUrl("/ext/ok")), Crawler.Options.defaults(), monitor);
            monitor.passStarted(2);
            crawler.run();
            monitor.passFinished(2, crawler);

            assertTrue(monitor.badLinks().isEmpty());
            assertEquals(List.of(Monitor.Kind.BROKEN, Monitor.Kind.PASS, Monitor.Kind.GONE, Monitor.Kind.PASS), kinds(monitor));
            Monitor.PassSummary last = monitor.lastPass();
            assertEquals(2, last.number());
            assertEquals(1, last.stats().ok());
        }
    }

    @Test
    void unverifiedLinksHaveTheirOwnEvent() {
        Monitor monitor = new Monitor(Duration.ofSeconds(1));
        monitor.onResult(result(LINK, 999, Outcome.BLOCKED, 10));
        assertEquals(List.of(Monitor.Kind.UNVERIFIED), kinds(monitor));
        assertEquals(1, monitor.badLinks().size());
    }

    @Test
    void tracksSlowLinksSlowestFirst() {
        Monitor monitor = new Monitor(Duration.ofMillis(500));
        URI other = URI.create("https://site.example/other");
        monitor.onResult(result(LINK, 200, Outcome.OK, 700));
        monitor.onResult(result(other, 200, Outcome.OK, 2000));
        monitor.onResult(result(URI.create("https://site.example/fast"), 200, Outcome.OK, 20));
        assertEquals(List.of(other, LINK), monitor.slowLinks().stream().map(LinkResult::url).toList());
        assertEquals(List.of(Monitor.Kind.SLOW, Monitor.Kind.SLOW), kinds(monitor));

        monitor.onResult(result(LINK, 200, Outcome.OK, 100)); // fast again
        assertEquals(List.of(other), monitor.slowLinks().stream().map(LinkResult::url).toList());

        monitor.onResult(result(other, 0, Outcome.BROKEN, 20_000)); // a timeout is broken, not slow
        assertTrue(monitor.slowLinks().isEmpty());
    }

    @Test
    void uptimeRecordsOnlyStartUrlsPerPass() {
        Monitor monitor = new Monitor(Duration.ofSeconds(1));
        URI other = URI.create("https://other.example/");
        monitor.passStarted(1);
        monitor.onResult(new LinkResult(PAGE, null, true, 200, Outcome.OK, "OK", Duration.ofMillis(30)));
        monitor.onResult(result(LINK, 404, Outcome.BROKEN, 10)); // found on a page: not on the timeline
        monitor.onResult(new LinkResult(other, null, true, 200, Outcome.OK, "OK", Duration.ofMillis(40)));
        monitor.passStarted(2);
        monitor.onResult(new LinkResult(PAGE, null, true, 0, Outcome.BROKEN, "timed out", Duration.ofSeconds(20)));

        Map<URI, List<Monitor.Check>> uptime = monitor.uptime();
        assertEquals(List.of(PAGE, other), List.copyOf(uptime.keySet()));
        assertEquals(List.of(1, 2), uptime.get(PAGE).stream().map(Monitor.Check::pass).toList());
        assertEquals(Outcome.BROKEN, uptime.get(PAGE).getLast().result().outcome());
        assertEquals(1, uptime.get(other).size());
    }
}
