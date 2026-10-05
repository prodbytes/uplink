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
            var options = new Crawler.Options(4, 16, Duration.ofSeconds(5), 100, Crawler.Options.DEFAULT_USER_AGENT, true, false);
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
            var options = new Crawler.Options(3, 64, Duration.ofSeconds(5), 100, Crawler.Options.DEFAULT_USER_AGENT, true, false);
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
            var options = new Crawler.Options(1, 1, Duration.ofSeconds(5), 10, Crawler.Options.DEFAULT_USER_AGENT, false, false);
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
    void pagesListedInDeclaredSitemapsAreCrawled() throws Exception {
        try (TestSite site = new TestSite()) {
            Crawler crawler = new Crawler(URI.create(site.sitemapSiteUrl("/")), Crawler.Options.defaults(), new CrawlListener() {
            });
            crawler.run();
            Map<String, LinkResult> byPath = crawler.results().stream()
                    .collect(Collectors.toMap(r -> (r.internal() ? "" : "ext:") + r.url().getPath(), Function.identity()));

            // The home page links nowhere; robots.txt -> sitemap index -> sitemap -> posts.
            assertOutcome(byPath, "/sitemap-index.xml", LinkResult.Outcome.OK, 200);
            assertOutcome(byPath, "/sitemap.xml", LinkResult.Outcome.OK, 200);
            assertOutcome(byPath, "/p/post", LinkResult.Outcome.OK, 200);
            assertOutcome(byPath, "/p/linked", LinkResult.Outcome.OK, 200);
            assertOutcome(byPath, "/p/gone", LinkResult.Outcome.BROKEN, 404);
            assertOutcome(byPath, "ext:/ext/ok", LinkResult.Outcome.OK, 200);
            assertEquals(URI.create(site.sitemapSiteUrl("/sitemap.xml")), byPath.get("/p/gone").referrer());
            assertEquals(URI.create(site.sitemapSiteUrl("/robots.txt")), byPath.get("/sitemap-index.xml").referrer());
            // robots.txt is read but is not a link; a sitemap on another site is ignored.
            assertTrue(!byPath.containsKey("/robots.txt"), byPath.keySet().toString());
            assertEquals(0, site.hits("/not-this-site.xml"));
            assertEquals(7, crawler.results().size(), byPath.keySet().toString());
            assertEquals(3, crawler.stats().pages(), "sitemaps are not counted as pages");
        }
    }

    @Test
    void aStartUrlThatRedirectsToAnotherSiteCrawlsThatSite() throws Exception {
        try (TestSite site = new TestSite()) {
            // Like nu01.com -> prodbytes.substack.com: the start URL is a doorway.
            Crawler crawler = new Crawler(URI.create(site.externalUrl("/moved")), Crawler.Options.defaults(), new CrawlListener() {
            });
            crawler.run();
            Map<String, LinkResult> byPath = crawler.results().stream()
                    .collect(Collectors.toMap(r -> (r.internal() ? "" : "ext:") + r.url().getPath(), Function.identity()));
            assertOutcome(byPath, "/moved", LinkResult.Outcome.OK, 200);
            // The target's sitemaps are read, and the posts they list are crawled.
            assertOutcome(byPath, "/p/post", LinkResult.Outcome.OK, 200);
            assertOutcome(byPath, "/p/linked", LinkResult.Outcome.OK, 200);
            assertTrue(byPath.get("/p/post").internal());
            assertEquals(URI.create(site.sitemapSiteUrl("/")), crawler.sites().getLast());
        }
    }

    @Test
    void maxDepthStopsCrawlingTheSiteButStillChecksTheLinksReached() throws Exception {
        try (TestSite site = new TestSite()) {
            // / (0) -> /a (1) -> /b (2) -> /forbidden (3)
            var options = new Crawler.Options(4, 16, Duration.ofSeconds(5), 100, Crawler.Options.DEFAULT_USER_AGENT,
                    true, false, 1, 0);
            Crawler crawler = new Crawler(URI.create(site.siteUrl()), options, new CrawlListener() {
            });
            crawler.run();
            Map<String, LinkResult> byPath = crawler.results().stream()
                    .collect(Collectors.toMap(r -> (r.internal() ? "" : "ext:") + r.url().getPath(), Function.identity()));
            assertOutcome(byPath, "/b", LinkResult.Outcome.OK, 200);
            assertTrue(!byPath.containsKey("/forbidden"), "links on a page past the depth limit are not followed");
            assertEquals(2, crawler.stats().pages(), "/ and /a are crawled, /b only checked");
        }
    }

    @Test
    void maxExternalDepthCrawlsOtherSitesThatFar() throws Exception {
        try (TestSite site = new TestSite()) {
            var options = new Crawler.Options(4, 16, Duration.ofSeconds(5), 100, Crawler.Options.DEFAULT_USER_AGENT,
                    true, false, Crawler.Options.UNLIMITED, 1);
            Crawler crawler = new Crawler(URI.create(site.siteUrl()), options, new CrawlListener() {
            });
            crawler.run();
            LinkResult deep = crawler.results().stream()
                    .filter(r -> r.url().getPath().equals("/ext/deep")).findFirst().orElseThrow();
            assertTrue(!deep.internal(), "pages reached on another site stay external");
            assertEquals(URI.create(site.externalUrl("/ext/page")), deep.referrer());
            // It 404s: HEAD, then the GET fallback.
            assertEquals(2, site.hits("/ext/deep"), "an external page's links are checked, one level deep");
        }
    }

    @Test
    void sitemapsCanBeIgnored() throws Exception {
        try (TestSite site = new TestSite()) {
            var options = new Crawler.Options(4, 16, Duration.ofSeconds(5), 100, Crawler.Options.DEFAULT_USER_AGENT, true, false);
            Crawler crawler = new Crawler(URI.create(site.sitemapSiteUrl("/")), options, new CrawlListener() {
            });
            crawler.run();
            assertEquals(1, crawler.results().size(), crawler.results().toString());
            assertEquals(0, site.hits("/robots.txt"));
        }
    }

    @Test
    void unreachableHostIsBroken() throws Exception {
        // Nothing listens on port 9 (discard) of the loopback interface.
        var options = new Crawler.Options(1, 1, Duration.ofSeconds(3), 10, Crawler.Options.DEFAULT_USER_AGENT, true, false);
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
