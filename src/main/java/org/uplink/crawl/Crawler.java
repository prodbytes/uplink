package org.uplink.crawl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    /** Largest HTML body parsed for links; anything beyond is ignored. */
    private static final int MAX_PAGE_BYTES = 10 * 1024 * 1024;

    /** Largest robots.txt read for {@code Sitemap:} lines. */
    private static final int MAX_ROBOTS_BYTES = 512 * 1024;

    /** Statuses meaning "the server refused the robot", not "the page is missing". */
    private static final Set<Integer> BLOCKED_STATUSES = Set.of(401, 403, 429, 999);

    /** Transient statuses worth one more attempt. */
    private static final Set<Integer> RETRY_STATUSES = Set.of(429, 502, 503, 504);

    private static final Map<Integer, String> REASONS = Map.ofEntries(
            Map.entry(400, "Bad Request"), Map.entry(401, "Unauthorized"), Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"), Map.entry(405, "Method Not Allowed"), Map.entry(408, "Request Timeout"),
            Map.entry(410, "Gone"), Map.entry(429, "Too Many Requests"), Map.entry(451, "Unavailable For Legal Reasons"),
            Map.entry(500, "Internal Server Error"), Map.entry(501, "Not Implemented"), Map.entry(502, "Bad Gateway"),
            Map.entry(503, "Service Unavailable"), Map.entry(504, "Gateway Timeout"),
            Map.entry(999, "Request Denied"));

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
                for (String line : text.split("\\R")) {
                    int colon = line.indexOf(':');
                    if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("sitemap")) {
                        Links.resolve(line.substring(colon + 1).trim())
                                .filter(uri -> Links.inSites(sites, uri))
                                .ifPresent(sitemaps::add);
                    }
                }
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
                    return new Checked(new LinkResult(url, referrer, internal, check.status, check.outcome,
                            check.detail, elapsed), check.links);
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

    private record Check(int status, LinkResult.Outcome outcome, String detail, List<URI> links) {

        /** A link to localhost outside the crawled sites: visitors cannot open it, and it is never requested. */
        static final Check LOCAL = new Check(0, LinkResult.Outcome.BLOCKED, "links to localhost", List.of());

        static Check of(int status) {
            LinkResult.Outcome outcome = status < 400 ? LinkResult.Outcome.OK
                    : BLOCKED_STATUSES.contains(status) ? LinkResult.Outcome.BLOCKED
                    : LinkResult.Outcome.BROKEN;
            return new Check(status, outcome, describe(status), List.of());
        }

        static Check failure(IOException e) {
            return new Check(0, LinkResult.Outcome.BROKEN, describe(e), List.of());
        }

        Check withLinks(List<URI> links) {
            return new Check(status, outcome, detail, links);
        }
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
            return Check.failure(e);
        }
        Check check = Check.of(response.statusCode());
        URI finalUri = response.uri();
        try (InputStream body = response.body()) {
            boolean html = isHtml(response.headers());
            boolean followable = check.outcome == LinkResult.Outcome.OK
                    && (html || isXml(response.headers()))
                    && Links.inSites(sites, finalUri)
                    && notYetSeenRedirectTarget(url, finalUri)
                    && stats.pages() < options.maxPages();
            if (!followable) {
                return check;
            }
            byte[] bytes = body.readNBytes(MAX_PAGE_BYTES);
            if (!html) {
                Document xml = Jsoup.parse(new ByteArrayInputStream(bytes), charset(response.headers()),
                        finalUri.toString(), Parser.xmlParser());
                return check.withLinks(new ArrayList<>(Links.extractSitemap(xml)));
            }
            stats.pages.incrementAndGet();
            Document doc = Jsoup.parse(new ByteArrayInputStream(bytes), charset(response.headers()), finalUri.toString());
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
            return Check.failure(e);
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
                    Thread.sleep(retryDelay(response));
                    continue;
                }
                return response;
            } catch (IOException e) {
                lastError = e;
                if (attempt == 1) {
                    Thread.sleep(500);
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

    private static long retryDelay(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .flatMap(v -> {
                    try {
                        return Optional.of(Long.parseLong(v.trim()));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                })
                .map(seconds -> Math.min(seconds, 10) * 1000)
                .orElse(2000L);
    }

    private static boolean isHtml(HttpHeaders headers) {
        String type = headers.firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        return type.contains("text/html") || type.contains("application/xhtml");
    }

    private static boolean isXml(HttpHeaders headers) {
        String type = headers.firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        return type.contains("/xml") || type.contains("+xml");
    }

    /** Charset from Content-Type, or {@code null} to let jsoup sniff it from the document. */
    private static String charset(HttpHeaders headers) {
        String type = headers.firstValue("Content-Type").orElse("");
        for (String part : type.split(";")) {
            String p = part.trim();
            if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                String cs = p.substring(8).replace("\"", "").trim();
                return cs.isEmpty() ? null : cs;
            }
        }
        return null;
    }

    static String describe(int status) {
        String reason = REASONS.get(status);
        if (reason != null) {
            return reason;
        }
        if (status < 300) {
            return "OK";
        }
        if (status < 400) {
            return "Redirect";
        }
        return status < 500 ? "Client Error" : "Server Error";
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
