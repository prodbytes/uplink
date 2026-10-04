package org.uplink.crawl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.uplink.TestSite;

class CrawlerTest {

    @Test
    void crawlsSiteRecursivelyAndChecksExternalLinksOnce() throws Exception {
        try (TestSite site = new TestSite()) {
            var started = new CopyOnWriteArrayList<URI>();
            var reported = new CopyOnWriteArrayList<LinkResult>();
            CrawlListener listener = new CrawlListener() {
                @Override
                public void onCheckStarted(URI url, boolean internal) {
                    started.add(url);
                }

                @Override
                public void onResult(LinkResult result) {
                    reported.add(result);
                }
            };
            var options = new Crawler.Options(4, 16, Duration.ofSeconds(5), 100, Crawler.Options.DEFAULT_USER_AGENT);
            Crawler crawler = new Crawler(URI.create(site.siteUrl()), options, listener);
            crawler.run();

            Map<String, LinkResult> byPath = crawler.results().stream()
                    .collect(Collectors.toMap(r -> (r.internal() ? "" : "ext:") + r.url().getPath(), Function.identity()));

            // Internal pages, including ones only reachable through other pages.
            assertOutcome(byPath, "/", LinkResult.Outcome.OK, 200);
            assertOutcome(byPath, "/a", LinkResult.Outcome.OK, 200);
            assertOutcome(byPath, "/b", LinkResult.Outcome.OK, 200);
            assertOutcome(byPath, "/doc.pdf", LinkResult.Outcome.OK, 200);
            assertOutcome(byPath, "/redirect", LinkResult.Outcome.OK, 200);
            assertOutcome(byPath, "/missing", LinkResult.Outcome.BROKEN, 404);
            assertOutcome(byPath, "/server-error", LinkResult.Outcome.BROKEN, 500);
            assertOutcome(byPath, "/img/missing.png", LinkResult.Outcome.BROKEN, 404);
            assertOutcome(byPath, "/forbidden", LinkResult.Outcome.BLOCKED, 403);

            // External links are checked (HEAD, then GET when HEAD is refused)...
            assertOutcome(byPath, "ext:/ext/ok", LinkResult.Outcome.OK, 200);
            assertOutcome(byPath, "ext:/ext/page", LinkResult.Outcome.OK, 200);
            assertOutcome(byPath, "ext:/ext/no-head", LinkResult.Outcome.OK, 200);
            assertOutcome(byPath, "ext:/ext/gone", LinkResult.Outcome.BROKEN, 404);
            // ...but never followed, and non-HTML bodies are never parsed.
            assertEquals(0, site.hits("/ext/deep"), "external page links must not be crawled");
            assertEquals(0, site.hits("/never-followed"), "links in a PDF must not be followed");

            // Each URL is checked exactly once, fragments and duplicates collapsed.
            assertEquals(13, crawler.results().size(), byPath.keySet().toString());
            assertEquals(13, started.size());
            assertEquals(13, reported.size());

            CrawlStats s = crawler.stats();
            assertTrue(s.finished());
            assertEquals(13, s.checked());
            assertEquals(8, s.ok());
            assertEquals(4, s.broken());
            assertEquals(1, s.blocked());
            assertEquals(0, s.inFlight());
            assertEquals(0, s.queued());
            assertEquals(3, s.pages(), "/, /a and /b are the internal HTML pages");

            assertEquals(URI.create(site.siteUrl() + "b"), byPath.get("/server-error").referrer());
        }
    }

    @Test
    void checksHostsInParallelOnVirtualThreadsWithinPerHostLimit() throws Exception {
        try (TestSite site = new TestSite()) {
            // 12 slow internal links and 12 slow external ones, 150ms each.
            var options = new Crawler.Options(3, 64, Duration.ofSeconds(5), 100, Crawler.Options.DEFAULT_USER_AGENT);
            Crawler crawler = new Crawler(URI.create(site.siteUrl() + "fanout"), options, new CrawlListener() {
            });
            long start = System.nanoTime();
            crawler.run();
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertEquals(25, crawler.stats().ok(), crawler.results().toString());
            assertEquals(3, site.peakConcurrency(false), "the crawled site gets at most --concurrency requests at once");
            assertTrue(site.peakConcurrency(true) <= 3, "every other host is limited the same way");
            assertTrue(site.peakConcurrency() > 3, "different hosts are checked in parallel");
            // Sequential would be 24 x 150ms = 3.6s; 3 per host on 2 hosts in parallel is ~0.6s.
            assertTrue(took.compareTo(Duration.ofMillis(2500)) < 0, "took " + took);
        }
    }

    @Test
    void unreachableHostIsBroken() throws Exception {
        // Nothing listens on port 9 (discard) of the loopback interface.
        var options = new Crawler.Options(1, 1, Duration.ofSeconds(3), 10, Crawler.Options.DEFAULT_USER_AGENT);
        Crawler crawler = new Crawler(URI.create("http://127.0.0.1:9/"), options, new CrawlListener() {
        });
        crawler.run();
        LinkResult result = crawler.results().getFirst();
        assertEquals(LinkResult.Outcome.BROKEN, result.outcome());
        assertEquals(0, result.status());
        assertEquals("connection refused", result.detail());
    }

    private static void assertOutcome(Map<String, LinkResult> byPath, String path, LinkResult.Outcome outcome, int status) {
        LinkResult r = byPath.get(path);
        assertTrue(r != null, "missing result for " + path + " in " + byPath.keySet());
        assertEquals(outcome, r.outcome(), path);
        assertEquals(status, r.status(), path);
    }
}
