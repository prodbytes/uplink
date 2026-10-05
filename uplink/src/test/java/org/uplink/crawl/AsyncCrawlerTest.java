package org.uplink.crawl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class AsyncCrawlerTest {

    private static final Crawler.Options OPTIONS =
            new Crawler.Options(2, 16, Duration.ofSeconds(5), 100, Crawler.Options.DEFAULT_USER_AGENT, true, true);

    /** An event loop with canned responses: callbacks and timers run only when {@link #drain()} is called. */
    static final class FakeTransport implements AsyncCrawler.Transport {
        final Map<String, Function<AsyncCrawler.Request, AsyncCrawler.Response>> routes = new HashMap<>();
        final ArrayDeque<Runnable> loop = new ArrayDeque<>();
        final List<String> sent = new ArrayList<>();
        final Map<String, Integer> activePerHost = new HashMap<>();
        int maxActivePerHost;

        void page(String url, String html) {
            routes.put(url, r -> ok(r.url(), "text/html; charset=utf-8", html));
        }

        void status(String url, int status) {
            routes.put(url, r -> AsyncCrawler.Response.received(status, r.url(), "text/plain", "", 1, Optional.empty()));
        }

        static AsyncCrawler.Response ok(URI url, String type, String body) {
            return AsyncCrawler.Response.received(200, url, type, body, 1, Optional.empty());
        }

        @Override
        public void send(AsyncCrawler.Request request, Consumer<AsyncCrawler.Response> callback) {
            String key = request.url().toString();
            sent.add(request.method() + " " + key);
            String host = request.url().getHost();
            int now = activePerHost.merge(host, 1, Integer::sum);
            maxActivePerHost = Math.max(maxActivePerHost, now);
            var route = routes.getOrDefault(key, r -> AsyncCrawler.Response.received(404, r.url(), "text/html", "", 1,
                    Optional.empty()));
            loop.add(() -> {
                activePerHost.merge(host, -1, Integer::sum);
                callback.accept(route.apply(request));
            });
        }

        @Override
        public void schedule(long delayMillis, Runnable task) {
            loop.add(task);
        }

        void drain() {
            while (!loop.isEmpty()) {
                loop.poll().run();
            }
        }
    }

    @Test
    void crawlsPagesChecksExternalLinksAndFinishes() {
        var transport = new FakeTransport();
        transport.page("https://site.test/", """
                <a href="/a">a</a> <a href="/missing">gone</a> <a href="https://ext.test/ok">ext</a>
                <a href="https://ext.test/no-head">no head</a> <a href="http://localhost:3000/">dev</a>
                <img src="/logo.png">""");
        transport.page("https://site.test/a", "<a href='/'>home</a> <a href='/b'>b</a>");
        transport.page("https://site.test/b", "<a href='https://ext.test/ok'>again</a>");
        transport.status("https://site.test/logo.png", 200);
        transport.status("https://ext.test/ok", 200);
        // HEAD refused, GET fine: the link is good.
        transport.routes.put("https://ext.test/no-head", r -> r.method().equals("HEAD")
                ? AsyncCrawler.Response.received(405, r.url(), "", "", 1, Optional.empty())
                : FakeTransport.ok(r.url(), "text/html", ""));

        var reported = new ArrayList<LinkResult>();
        var crawler = new AsyncCrawler(List.of(URI.create("https://site.test/")), OPTIONS, new CrawlListener() {
            @Override
            public void onResult(LinkResult result) {
                reported.add(result);
            }
        }, transport);
        boolean[] done = {false};
        crawler.start(() -> done[0] = true);
        transport.drain();

        assertTrue(done[0], "onDone must run once the crawl ends");
        assertTrue(crawler.isFinished());
        Map<String, LinkResult> byUrl = crawler.results().stream()
                .collect(Collectors.toMap(r -> r.url().toString(), Function.identity()));
        assertEquals(LinkResult.Outcome.OK, byUrl.get("https://site.test/b").outcome(), "pages reached through other pages");
        assertEquals(404, byUrl.get("https://site.test/missing").status());
        assertEquals(LinkResult.Outcome.BROKEN, byUrl.get("https://site.test/missing").outcome());
        assertEquals(LinkResult.Outcome.OK, byUrl.get("https://ext.test/no-head").outcome());
        assertEquals(LinkResult.Outcome.BLOCKED, byUrl.get("http://localhost:3000/").outcome());
        assertEquals(8, crawler.results().size(), "each unique link is checked once");
        assertEquals(reported.size(), crawler.results().size());
        assertEquals(3, crawler.stats().pages());
        assertEquals(1, transport.sent.stream().filter(s -> s.equals("HEAD https://ext.test/ok")).count(),
                "external links are checked once");
        assertFalse(transport.sent.contains("GET http://localhost:3000/"), "localhost links are never requested");
        assertEquals(0, crawler.stats().inFlight());
    }

    @Test
    void sitemapsFromRobotsTxtSeedTheCrawl() {
        var transport = new FakeTransport();
        transport.page("https://site.test/", "no links");
        transport.routes.put("https://site.test/robots.txt", r -> FakeTransport.ok(r.url(), "text/plain",
                "User-agent: *\nSitemap: https://site.test/sitemap.xml\nSitemap: https://elsewhere.test/sitemap.xml\n"));
        transport.routes.put("https://site.test/sitemap.xml", r -> FakeTransport.ok(r.url(), "application/xml", """
                <?xml version="1.0"?><urlset><url><loc>https://site.test/hidden</loc></url></urlset>"""));
        transport.page("https://site.test/hidden", "found via sitemap");

        var crawler = new AsyncCrawler(List.of(URI.create("https://site.test/")), OPTIONS, new CrawlListener() {
        }, transport);
        crawler.start(() -> { });
        transport.drain();

        List<String> urls = crawler.results().stream().map(r -> r.url().toString()).toList();
        assertTrue(urls.contains("https://site.test/hidden"), urls.toString());
        assertFalse(urls.contains("https://site.test/robots.txt"), "robots.txt itself is not reported");
        assertFalse(transport.sent.contains("GET https://elsewhere.test/sitemap.xml"), "sitemaps on other sites are ignored");
    }

    @Test
    void retriesTransientStatusOnceAndRespectsTheHostLimit() {
        var transport = new FakeTransport();
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            html.append("<a href='/p").append(i).append("'>p</a>");
            transport.page("https://site.test/p" + i, "leaf");
        }
        html.append("<a href='/flaky'>flaky</a>");
        transport.page("https://site.test/", html.toString());
        int[] calls = {0};
        transport.routes.put("https://site.test/flaky", r -> ++calls[0] == 1
                ? AsyncCrawler.Response.received(503, r.url(), "", "", 1, Optional.empty())
                : FakeTransport.ok(r.url(), "text/html", ""));

        var crawler = new AsyncCrawler(List.of(URI.create("https://site.test/")), OPTIONS, new CrawlListener() {
        }, transport);
        crawler.start(() -> { });
        transport.drain();

        LinkResult flaky = crawler.results().stream()
                .filter(r -> r.url().getPath().equals("/flaky")).findFirst().orElseThrow();
        assertEquals(200, flaky.status());
        assertEquals(2, calls[0]);
        assertTrue(transport.maxActivePerHost <= OPTIONS.perHost(),
                "at most " + OPTIONS.perHost() + " concurrent requests per host, saw " + transport.maxActivePerHost);
    }

    @Test
    void hiddenStatusCountsAsReachableButIsNotFollowed() {
        var transport = new FakeTransport();
        transport.routes.put("https://site.test/", r -> AsyncCrawler.Response.hidden(r.url()));

        var crawler = new AsyncCrawler(List.of(URI.create("https://site.test/")), OPTIONS, new CrawlListener() {
        }, transport);
        crawler.start(() -> { });
        transport.drain();

        LinkResult root = crawler.results().getFirst();
        assertEquals(LinkResult.Outcome.OK, root.outcome());
        assertEquals(AsyncCrawler.STATUS_HIDDEN, root.detail());
        assertEquals(0, crawler.stats().pages());
    }

    @Test
    void unverifiableLinksAreNotRetried() {
        var transport = new FakeTransport();
        transport.page("https://site.test/", "<a href='http://plain.test/'>http</a>");
        transport.routes.put("http://plain.test/", r -> AsyncCrawler.Response.unverified(r.url(), "blocked by the browser"));

        var crawler = new AsyncCrawler(List.of(URI.create("https://site.test/")), OPTIONS, new CrawlListener() {
        }, transport);
        crawler.start(() -> { });
        transport.drain();

        LinkResult plain = crawler.results().stream()
                .filter(r -> r.url().getHost().equals("plain.test")).findFirst().orElseThrow();
        assertEquals(LinkResult.Outcome.BLOCKED, plain.outcome());
        assertEquals("blocked by the browser", plain.detail());
        assertEquals(1, transport.sent.stream().filter(s -> s.endsWith("http://plain.test/")).count());
    }

    @Test
    void networkErrorsAreRetriedThenBroken() {
        var transport = new FakeTransport();
        transport.page("https://site.test/", "<a href='https://down.test/'>down</a>");
        transport.routes.put("https://down.test/", r -> AsyncCrawler.Response.failed(r.url(), "network error"));

        var crawler = new AsyncCrawler(List.of(URI.create("https://site.test/")), OPTIONS, new CrawlListener() {
        }, transport);
        crawler.start(() -> { });
        transport.drain();

        LinkResult down = crawler.results().stream()
                .filter(r -> r.url().getHost().equals("down.test")).findFirst().orElseThrow();
        assertEquals(LinkResult.Outcome.BROKEN, down.outcome());
        assertEquals("network error", down.detail());
        // HEAD twice, then the GET fallback twice.
        assertEquals(4, transport.sent.stream().filter(s -> s.endsWith("https://down.test/")).count());
    }

    @Test
    void cancelStopsAtOnceWithPartialResults() {
        var transport = new FakeTransport();
        transport.page("https://site.test/", "<a href='/a'>a</a>");
        transport.page("https://site.test/a", "leaf");
        boolean[] done = {false};
        var crawler = new AsyncCrawler(List.of(URI.create("https://site.test/")), OPTIONS, new CrawlListener() {
        }, transport);
        crawler.start(() -> done[0] = true);
        crawler.cancel();
        assertTrue(done[0]);
        transport.drain();
        assertTrue(crawler.isCancelled());
        assertTrue(crawler.results().isEmpty(), "results arriving after cancel are dropped");
    }

    @Test
    void cancelAfterTheCrawlFinishedChangesNothing() {
        var transport = new FakeTransport();
        transport.page("https://site.test/", "leaf");
        var crawler = new AsyncCrawler(List.of(URI.create("https://site.test/")), OPTIONS, new CrawlListener() {
        }, transport);
        crawler.start(() -> { });
        transport.drain();
        crawler.cancel();
        assertFalse(crawler.isCancelled(), "a finished pass is reported as finished, not cancelled");
    }
}
