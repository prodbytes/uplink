package org.uplink.crawl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.uplink.crawl.CheckRules.Check;

import static org.uplink.crawl.CheckRules.ERROR_RETRY_MILLIS;
import static org.uplink.crawl.CheckRules.MAX_PAGE_BYTES;
import static org.uplink.crawl.CheckRules.MAX_ROBOTS_BYTES;
import static org.uplink.crawl.CheckRules.RETRY_STATUSES;
import static org.uplink.crawl.CheckRules.charset;
import static org.uplink.crawl.CheckRules.isHtml;
import static org.uplink.crawl.CheckRules.isXml;
import static org.uplink.crawl.CheckRules.retryDelayMillis;

/**
 * Breadth-first link checker. Pages on the start URL's site, or on any other allowed
 * site, are fetched, parsed and their links followed; links to other sites are checked
 * once and never followed.
 */
public final class Crawler {

    /**
     * @param perHost     most concurrent requests to any one host (politeness towards the crawled site)
     * @param maxInFlight most concurrent requests overall
     * @param followRedirects whether redirects are followed (except HTTPS to HTTP); when not, a 3xx counts as OK
     * @param sitemaps    whether the sitemaps a site declares in its robots.txt seed the crawl
     */
    public record Options(int perHost, int maxInFlight, Duration timeout, int maxPages, String userAgent,
            boolean followRedirects, boolean sitemaps) {

        public static final String DEFAULT_USER_AGENT =
                "Mozilla/5.0 (compatible; uplink/1.0; link checker) AppleWebKit/537.36 (KHTML, like Gecko)";

        public static Options defaults() {
            return new Options(8, 64, Duration.ofSeconds(20), 10_000, DEFAULT_USER_AGENT, true, true);
        }
    }

    private final URI root;
    /** Sites whose pages are crawled: the root's first, then any others allowed. */
    private final List<URI> sites;
    private final Options options;
    private final CrawlListener listener;
    private final HttpClient client;
    private final CrawlStats stats = new CrawlStats();
    private final Set<URI> seen = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<LinkResult> results = new ConcurrentLinkedQueue<>();
    /**
     * Every link is checked on its own virtual thread; blocking on a permit or on I/O
     * parks the virtual thread without tying up a carrier thread.
     */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** The HTTP client's internal async work runs on virtual threads as well. */
    private final ExecutorService httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore globalPermits;
    private final Map<String, Semaphore> hostPermits = new ConcurrentHashMap<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile boolean cancelled;

    public Crawler(URI root, Options options, CrawlListener listener) {
        this(List.of(root), options, listener);
    }

    /**
     * @param sites the start URL, followed by any other sites whose pages are crawled too
     */
    public Crawler(List<URI> sites, Options options, CrawlListener listener) {
        if (sites.isEmpty()) {
            throw new IllegalArgumentException("No start URL");
        }
        this.sites = sites.stream()
                .map(site -> Links.normalize(site).orElseThrow(() -> new IllegalArgumentException("Not an http(s) URL: " + site)))
                .toList();
        this.root = this.sites.getFirst();
        this.options = options;
        this.listener = listener;
        this.globalPermits = new Semaphore(options.maxInFlight());
        this.client = HttpClient.newBuilder()
                .executor(httpExecutor)
                .followRedirects(options.followRedirects() ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
                .connectTimeout(options.timeout())
                .build();
    }

    public URI root() {
        return root;
    }

    public List<URI> sites() {
        return sites;
    }

    public CrawlStats stats() {
        return stats;
    }

    /** Snapshot of every result so far. */
    public List<LinkResult> results() {
        return new ArrayList<>(results);
    }

    /** Crawls until every reachable link has been checked or {@link #cancel()} is called. */
    public void run() throws InterruptedException {
        try {
            submit(root, null);
            if (options.sitemaps()) {
                for (URI site : sites) {
                    discoverSitemaps(site);
                }
            }
            done.await();
        } finally {
            stats.finish();
            executor.shutdownNow();
            client.shutdownNow();
            httpExecutor.shutdownNow();
        }
    }

    /** Stops the crawl; {@link #run()} returns promptly with partial results. */
    public void cancel() {
        cancelled = true;
        done.countDown();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    private void submit(URI url, URI referrer) {
        if (cancelled || !seen.add(url)) {
            return;
        }
        stats.discovered.incrementAndGet();
        pending.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    process(url, referrer);
                } finally {
                    taskDone();
                }
            });
        } catch (RejectedExecutionException e) {
            taskDone();
        }
    }

    /**
     * Reads the site's robots.txt in the background and queues the sitemaps it declares,
     * so pages that no HTML links to (lists built by JavaScript, like Substack's home
     * page) are still crawled. robots.txt itself is not a link anyone followed, so it is
     * not reported, and a site without one is not an error.
     */
    private void discoverSitemaps(URI site) {
        pending.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    URI robots = site.resolve("/robots.txt");
                    for (URI sitemap : withPermits(robots, true, () -> fetchSitemapUrls(robots))) {
                        submit(sitemap, robots);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    taskDone();
                }
            });
        } catch (RejectedExecutionException e) {
            taskDone();
        }
    }

    /** The {@code Sitemap:} URLs in a robots.txt that are on a crawled site. */
    private List<URI> fetchSitemapUrls(URI robots) throws InterruptedException {
        List<URI> sitemaps = new ArrayList<>();
        try {
            HttpResponse<InputStream> response = sendWithRetry(request(robots, "GET"), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    return sitemaps;
                }
                String text = new String(body.readNBytes(MAX_ROBOTS_BYTES), StandardCharsets.UTF_8);
                sitemaps.addAll(CheckRules.sitemapUrls(text, sites));
            }
        } catch (IOException ignored) {
            // No robots.txt to read: the crawl just starts from the start URL alone.
        }
        return sitemaps;
    }

    private interface Task<T> {
        T run() throws InterruptedException;
    }

    /** Runs a request under the same per-host and global limits as link checks. */
    private <T> T withPermits(URI url, boolean internal, Task<T> task) throws InterruptedException {
        // Always host first, then global: a thread never holds a global permit while
        // waiting for a host one, so slow hosts cannot starve the others.
        Semaphore hostPermit = hostPermits.computeIfAbsent(hostKey(url, internal), k -> new Semaphore(options.perHost()));
        hostPermit.acquire();
        try {
            globalPermits.acquire();
            try {
                return task.run();
            } finally {
                globalPermits.release();
            }
        } finally {
            hostPermit.release();
        }
    }

    private void taskDone() {
        if (pending.decrementAndGet() == 0) {
            done.countDown();
        }
    }

    private void process(URI url, URI referrer) {
        if (cancelled) {
            return;
        }
        boolean internal = Links.inSites(sites, url);
        record Checked(LinkResult result, List<URI> links) {
        }
        Checked checked;
        try {
            checked = withPermits(url, internal, () -> {
                stats.inFlight.incrementAndGet();
                try {
                    listener.onCheckStarted(url, internal);
                    long start = System.nanoTime();
                    Check check = internal ? fetchPage(url)
                            : Links.isLocal(url) ? Check.LOCAL
                            : checkExternal(url);
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                    return new Checked(new LinkResult(url, referrer, internal, check.status(), check.outcome(),
                            check.detail(), elapsed), check.links());
                } finally {
                    stats.inFlight.decrementAndGet();
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (cancelled) {
            return;
        }
        LinkResult result = checked.result();
        results.add(result);
        stats.record(result);
        listener.onResult(result);
        for (URI child : checked.links()) {
            submit(child, url);
        }
    }

    /** Each crawled site (with and without www.) shares one limit; other hosts get their own. */
    private static String hostKey(URI url, boolean internal) {
        return (internal ? Links.siteHost(url) : url.getHost()) + ":" + url.getPort();
    }

    /**
     * GETs an internal URL and, when it is an HTML page on this site, extracts its links;
     * when it is an XML sitemap, the URLs it lists.
     */
    private Check fetchPage(URI url) throws InterruptedException {
        HttpResponse<InputStream> response;
        try {
            response = sendWithRetry(request(url, "GET"), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            return Check.failure(describe(e));
        }
        Check check = Check.of(response.statusCode());
        URI finalUri = response.uri();
        try (InputStream body = response.body()) {
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            boolean html = isHtml(contentType);
            boolean followable = check.outcome() == LinkResult.Outcome.OK
                    && (html || isXml(contentType))
                    && Links.inSites(sites, finalUri)
                    && notYetSeenRedirectTarget(url, finalUri)
                    && stats.pages() < options.maxPages();
            if (!followable) {
                return check;
            }
            byte[] bytes = body.readNBytes(MAX_PAGE_BYTES);
            if (!html) {
                Document xml = Jsoup.parse(new ByteArrayInputStream(bytes), charset(contentType),
                        finalUri.toString(), Parser.xmlParser());
                return check.withLinks(new ArrayList<>(Links.extractSitemap(xml)));
            }
            stats.pages.incrementAndGet();
            Document doc = Jsoup.parse(new ByteArrayInputStream(bytes), charset(contentType), finalUri.toString());
            return check.withLinks(new ArrayList<>(Links.extract(doc)));
        } catch (IOException e) {
            // The status was already received; a failure while reading the body only
            // means this page's links cannot be followed.
            return check;
        }
    }

    /**
     * A redirect to a page that is already (being) crawled must not be parsed twice;
     * a redirect to a new page claims it so it is not fetched again later.
     */
    private boolean notYetSeenRedirectTarget(URI requested, URI finalUri) {
        URI target = Links.normalize(finalUri).orElse(requested);
        return target.equals(requested) || seen.add(target);
    }

    /**
     * Checks an external URL with HEAD, falling back to GET because plenty of servers
     * answer HEAD with 403/404/405 (or not at all) while serving GET fine.
     */
    private Check checkExternal(URI url) throws InterruptedException {
        try {
            HttpResponse<Void> head = sendWithRetry(request(url, "HEAD"), HttpResponse.BodyHandlers.discarding());
            if (head.statusCode() < 400) {
                return Check.of(head.statusCode());
            }
        } catch (IOException ignored) {
            // fall through to GET
        }
        try {
            HttpResponse<InputStream> get = sendWithRetry(request(url, "GET"), HttpResponse.BodyHandlers.ofInputStream());
            // Only the status matters: closing the stream aborts the download.
            get.body().close();
            return Check.of(get.statusCode());
        } catch (IOException e) {
            return Check.failure(describe(e));
        }
    }

    private HttpRequest request(URI url, String method) {
        return HttpRequest.newBuilder(url)
                .timeout(options.timeout())
                .header("User-Agent", options.userAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
    }

    private <T> HttpResponse<T> sendWithRetry(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (cancelled) {
                throw new IOException("cancelled");
            }
            try {
                HttpResponse<T> response;
                try {
                    response = client.send(request, handler);
                } catch (IOException e) {
                    stats.requests.incrementAndGet();
                    throw e;
                }
                stats.requests.addAndGet(hops(response));
                if (attempt == 1 && RETRY_STATUSES.contains(response.statusCode())) {
                    if (response.body() instanceof InputStream in) {
                        in.close();
                    }
                    Thread.sleep(retryDelayMillis(response.headers().firstValue("Retry-After")));
                    continue;
                }
                return response;
            } catch (IOException e) {
                lastError = e;
                if (attempt == 1) {
                    Thread.sleep(ERROR_RETRY_MILLIS);
                }
            }
        }
        // Only reachable when the second attempt failed with an exception.
        throw lastError;
    }

    /** Requests behind a response: one, plus one per redirect the client followed to get it. */
    private static int hops(HttpResponse<?> response) {
        int hops = 1;
        for (var previous = response.previousResponse(); previous.isPresent(); previous = previous.get().previousResponse()) {
            hops++;
        }
        return hops;
    }

    static String describe(IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
                return "DNS lookup failed";
            }
            if (t instanceof HttpConnectTimeoutException) {
                return "connection timed out";
            }
            if (t instanceof HttpTimeoutException) {
                return "request timed out";
            }
            if (t instanceof ConnectException) {
                return "connection refused";
            }
            if (t instanceof SSLException) {
                return "TLS error: " + t.getMessage();
            }
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
