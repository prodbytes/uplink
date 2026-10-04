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
            var options = new Crawler.Options(4, 16, Duration.ofSeconds(5), 100, Crawler.Options.DEFAULT_USER_AGENT, true, true);
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
            // Links to localhost outside the site are warnings and never requested.
            assertOutcome(byPath, "ext:/local-only", LinkResult.Outcome.BLOCKED, 0);
            assertEquals(0, site.hits("/local-only"), "localhost links must not be requested");

            // Each URL is checked exactly once, fragments and duplicates collapsed.
            assertEquals(14, crawler.results().size(), byPath.keySet().toString());
            assertEquals(14, started.size());
            assertEquals(14, reported.size());

            CrawlStats s = crawler.stats();
            assertTrue(s.finished());
            assertEquals(14, s.checked());
            assertEquals(8, s.ok());
            assertEquals(4, s.broken());
            assertEquals(2, s.blocked());
            assertEquals(0, s.inFlight());
            assertEquals(0, s.queued());
            assertEquals(3, s.pages(), "/, /a and /b are the internal HTML pages");
            // 9 internal GETs plus the hop behind /redirect; 4 external HEADs plus a GET
            // for /ext/no-head (HEAD refused) and /ext/gone (HEAD 404, confirmed by GET).
            assertEquals(16, s.requests());

            assertEquals(URI.create(site.siteUrl() + "b"), byPath.get("/server-error").referrer());
        }
    }

    @Test
    void checksHostsInParallelOnVirtualThreadsWithinPerHostLimit() throws Exception {
        try (TestSite site = new TestSite()) {
            // 12 slow internal links and 12 slow external ones, 150ms each.
            var options = new Crawler.Options(3, 64, Duration.ofSeconds(5), 100, Crawler.Options.DEFAULT_USER_AGENT, true, true);
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
    void redirectsCanBeLeftUnfollowed() throws Exception {
        try (TestSite site = new TestSite()) {
            var options = new Crawler.Options(1, 1, Duration.ofSeconds(5), 10, Crawler.Options.DEFAULT_USER_AGENT, false, true);
            Crawler crawler = new Crawler(URI.create(site.siteUrl() + "redirect"), options, new CrawlListener() {
            });
            crawler.run();
            LinkResult result = crawler.results().getFirst();
            assertEquals(LinkResult.Outcome.OK, result.outcome());
            assertEquals(301, result.status());
            assertEquals(1, crawler.results().size(), "the redirect target is neither checked nor crawled");
        }
    }

    @Test
    void allowedSitesAreCrawledToo() throws Exception {
        try (TestSite site = new TestSite()) {
            var sites = Links.parseSites(site.siteUrl() + "," + site.externalUrl("/")).orElseThrow();
            Crawler crawler = new Crawler(sites, Crawler.Options.defaults(), new CrawlListener() {
            });
            crawler.run();
            LinkResult page = crawler.results().stream()
                    .filter(r -> r.url().getPath().equals("/ext/page")).findFirst().orElseThrow();
            assertTrue(page.internal(), "a link to an allowed site is internal");
            assertEquals(1, site.hits("/ext/deep"), "pages on an allowed site are crawled");
        }
    }

    @Test
    void crawlsPagesListedInSitemaps() throws Exception {
        try (TestSite site = new TestSite().withSitemaps()) {
            Crawler crawler = new Crawler(URI.create(site.siteUrl()), Crawler.Options.defaults(), new CrawlListener() {
            });
            crawler.run();
            Map<String, LinkResult> byPath = crawler.results().stream()
                    .filter(LinkResult::internal)
                    .collect(Collectors.toMap(r -> r.url().getPath(), Function.identity()));

            URI sitemap = URI.create(site.siteUrl() + "sitemap-pages.xml");
            assertOutcome(byPath, "/orphan", LinkResult.Outcome.OK, 200);
            assertEquals(sitemap, byPath.get("/orphan").referrer(), "a page found in a sitemap is reported as found there");
            assertOutcome(byPath, "/orphan-child", LinkResult.Outcome.OK, 200);
            assertOutcome(byPath, "/gone-orphan", LinkResult.Outcome.BROKEN, 404);
            assertEquals(1, site.hits("/"), "a page both linked and listed is fetched once");
            assertEquals(1, site.hits("/sitemap-index.xml"), "a sitemap index listing itself is read once");
            assertEquals(0, site.hits("/ext/from-sitemap"), "sitemap entries on other sites are not checked");
            assertEquals(0, site.hits("/orphan.png"), "image entries are not pages");
        }
    }

    @Test
    void sitemapsCanBeSkipped() throws Exception {
        try (TestSite site = new TestSite().withSitemaps()) {
            var options = new Crawler.Options(4, 16, Duration.ofSeconds(5), 100, Crawler.Options.DEFAULT_USER_AGENT, true, false);
            new Crawler(URI.create(site.siteUrl()), options, new CrawlListener() {
            }).run();
            assertEquals(0, site.hits("/robots.txt"));
            assertEquals(0, site.hits("/orphan"));
        }
    }

    @Test
    void unreachableHostIsBroken() throws Exception {
        // Nothing listens on port 9 (discard) of the loopback interface.
        var options = new Crawler.Options(1, 1, Duration.ofSeconds(3), 10, Crawler.Options.DEFAULT_USER_AGENT, true, true);
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
