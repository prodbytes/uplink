package org.uplink.crawl;

import static org.uplink.crawl.CheckRules.ERROR_RETRY_MILLIS;
import static org.uplink.crawl.CheckRules.MAX_PAGE_BYTES;
import static org.uplink.crawl.CheckRules.MAX_ROBOTS_BYTES;
import static org.uplink.crawl.CheckRules.RETRY_STATUSES;
import static org.uplink.crawl.CheckRules.isHtml;
import static org.uplink.crawl.CheckRules.isXml;
import static org.uplink.crawl.CheckRules.retryDelayMillis;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.uplink.crawl.CheckRules.Check;
import org.uplink.crawl.CheckRules.Depth;

/**
 * The same crawl as {@link Crawler}, for a single-threaded event loop such as a
 * browser's: requests go through a {@link Transport} that calls back when they finish,
 * and nothing blocks. The per-host and overall limits of {@link Crawler.Options} are
 * kept by queueing requests instead of holding semaphores.
 *
 * <p>Not thread-safe: every call, transport callbacks included, must come from the
 * same thread.
 */
public final class AsyncCrawler {

    /** Sends requests and runs delayed work on the event loop. */
    public interface Transport {

        /** Sends {@code request}; {@code callback} runs exactly once, normally later. */
        void send(Request request, Consumer<Response> callback);

        /** Runs {@code task} on the event loop after {@code delayMillis}. */
        void schedule(long delayMillis, Runnable task);
    }

    /**
     * @param readBody whether the body is needed: internal pages and robots.txt, not link checks
     */
    public record Request(URI url, String method, boolean readBody) {
    }

    /** What became of a request. */
    public enum Kind {
        /** A response whose status could be read. */
        RECEIVED,
        /**
         * The server answered but its status cannot be read, as with a cross-origin
         * response a browser receives without CORS headers. The link counts as good.
         */
        HIDDEN,
        /** The request could not be made from here (say, blocked by the browser); the link is unverified. */
        UNVERIFIED,
        /** No response: DNS, connection, TLS or timeout. Retried once, then the link is broken. */
        FAILED
    }

    /**
     * @param kind        what became of the request; only {@link Kind#RECEIVED} carries the fields below
     * @param status      HTTP status
     * @param finalUri    where the request ended up after redirects
     * @param contentType the Content-Type header, empty when unknown
     * @param body        the body when it was asked for, else empty
     * @param hops        requests behind the response: one, plus one per redirect followed
     * @param retryAfter  the Retry-After header, when readable
     * @param detail      why the request failed or was not verified, for {@link Kind#FAILED} and {@link Kind#UNVERIFIED}
     */
    public record Response(Kind kind, int status, URI finalUri, String contentType, String body, int hops,
            Optional<String> retryAfter, String detail) {

        public static Response received(int status, URI finalUri, String contentType, String body, int hops,
                Optional<String> retryAfter) {
            return new Response(Kind.RECEIVED, status, finalUri, contentType, body, hops, retryAfter, "");
        }

        public static Response hidden(URI url) {
            return new Response(Kind.HIDDEN, 0, url, "", "", 1, Optional.empty(), STATUS_HIDDEN);
        }

        public static Response unverified(URI url, String reason) {
            return new Response(Kind.UNVERIFIED, 0, url, "", "", 0, Optional.empty(), reason);
        }

        public static Response failed(URI url, String error) {
            return new Response(Kind.FAILED, 0, url, "", "", 1, Optional.empty(), error);
        }
    }

    /** Detail of a link that answered but whose status could not be read. */
    public static final String STATUS_HIDDEN = "reachable, status hidden (no CORS)";

    private static final Check HIDDEN = new Check(0, LinkResult.Outcome.OK, STATUS_HIDDEN, List.of());

    /** The check a response that was not {@link Kind#RECEIVED} amounts to. */
    private static Check unreceived(Response response) {
        return switch (response.kind()) {
            case HIDDEN -> HIDDEN;
            case UNVERIFIED -> new Check(0, LinkResult.Outcome.BLOCKED, response.detail(), List.of());
            case FAILED, RECEIVED -> Check.failure(response.detail());
        };
    }

    /** A queued request: runs once a permit is free, and calls {@code release} when done with it. */
    private interface Job {
        void run(Runnable release);
    }

    private final URI root;
    private final List<URI> sites;
    private final Crawler.Options options;
    private final CrawlListener listener;
    private final Transport transport;
    private final CrawlStats stats = new CrawlStats();
    private final Set<URI> seen = new HashSet<>();
    private final List<LinkResult> results = new ArrayList<>();
    /** Requests waiting for a permit, per host key, in the order hosts first had to wait. */
    private final Map<String, ArrayDeque<Job>> waiting = new LinkedHashMap<>();
    private final Map<String, Integer> activePerHost = new HashMap<>();
    private int active;
    private int pending;
    private boolean pumping;
    private boolean pumpAgain;
    private boolean started;
    private boolean finished;
    private boolean cancelled;
    private Runnable onDone = () -> { };

    /**
     * @param sites the start URL, followed by any other sites whose pages are crawled too
     */
    public AsyncCrawler(List<URI> sites, Crawler.Options options, CrawlListener listener, Transport transport) {
        if (sites.isEmpty()) {
            throw new IllegalArgumentException("No start URL");
        }
        this.sites = new ArrayList<>(sites.stream()
                .map(site -> Links.normalize(site).orElseThrow(() -> new IllegalArgumentException("Not an http(s) URL: " + site)))
                .toList());
        this.root = this.sites.getFirst();
        this.options = options;
        this.listener = listener;
        this.transport = transport;
    }

    public URI root() {
        return root;
    }

    /** The crawled sites, including any a start URL redirected to. */
    public List<URI> sites() {
        return List.copyOf(sites);
    }

    public CrawlStats stats() {
        return stats;
    }

    /** Snapshot of every result so far. */
    public List<LinkResult> results() {
        return new ArrayList<>(results);
    }

    /**
     * Starts crawling from the start URL and returns at once; {@code onDone} runs when
     * every reachable link has been checked, or when {@link #cancel()} is called.
     */
    public void start(Runnable onDone) {
        start(List.of(root), onDone);
    }

    /**
     * Like {@link #start(Runnable)}, but crawls from each of {@code seeds}, so sites that
     * do not link to each other are all crawled in one pass.
     *
     * @param seeds URLs to start from, usually {@link #sites()}
     */
    public void start(List<URI> seeds, Runnable onDone) {
        if (started) {
            throw new IllegalStateException("Already started");
        }
        started = true;
        this.onDone = onDone;
        // Held while seeding, so the crawl cannot finish before the sitemaps are queued.
        pending++;
        for (URI seed : seeds) {
            Links.normalize(seed).ifPresent(url -> submit(url, null, Depth.START));
        }
        if (options.sitemaps()) {
            for (URI site : List.copyOf(sites)) {
                discoverSitemaps(site);
            }
        }
        taskDone();
    }

    /**
     * Stops the crawl: queued requests are dropped and {@code onDone} runs with partial
     * results. A crawl that has already finished is left as it is.
     */
    public void cancel() {
        if (finished) {
            return;
        }
        cancelled = true;
        waiting.clear();
        finish();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isFinished() {
        return finished;
    }

    private void finish() {
        if (!finished) {
            finished = true;
            stats.finish();
            onDone.run();
        }
    }

    private void taskDone() {
        if (--pending == 0) {
            finish();
        }
    }

    private void submit(URI url, URI referrer, Depth depth) {
        if (cancelled || !seen.add(url)) {
            return;
        }
        stats.discovered.incrementAndGet();
        pending++;
        boolean internal = Links.inSites(sites, url);
        boolean crawled = depth.crawled(options);
        withPermit(url, internal, release -> {
            stats.inFlight.incrementAndGet();
            listener.onCheckStarted(url, internal);
            long start = System.nanoTime();
            Consumer<Check> done = check -> {
                stats.inFlight.decrementAndGet();
                release.run();
                if (!cancelled) {
                    LinkResult result = new LinkResult(url, referrer, internal, check.status(), check.outcome(),
                            check.detail(), Duration.ofNanos(System.nanoTime() - start));
                    results.add(result);
                    stats.record(result);
                    listener.onResult(result);
                    for (URI child : check.links()) {
                        boolean childInternal = Links.inSites(sites, child);
                        submit(child, url, check.listed() ? depth.listed(childInternal) : depth.child(childInternal));
                    }
                }
                taskDone();
            };
            if (internal) {
                fetchPage(url, true, crawled, referrer == null, done);
            } else if (Links.isLocal(url)) {
                done.accept(Check.LOCAL);
            } else if (crawled) {
                fetchPage(url, false, true, false, done);
            } else {
                checkExternal(url, done);
            }
        });
    }

    /**
     * Reads the site's robots.txt and queues the sitemaps it declares, so pages that no
     * HTML links to are still crawled. As in {@link Crawler}, robots.txt itself is not
     * reported, and a site without one is not an error.
     */
    private void discoverSitemaps(URI site) {
        pending++;
        URI robots = site.resolve("/robots.txt");
        withPermit(robots, true, release -> sendWithRetry(new Request(robots, "GET", true), response -> {
            release.run();
            if (!cancelled && response.kind() == Kind.RECEIVED && response.status() == 200) {
                for (URI sitemap : CheckRules.sitemapUrls(truncate(response.body(), MAX_ROBOTS_BYTES), sites)) {
                    submit(sitemap, robots, Depth.START);
                }
            }
            taskDone();
        }));
    }

    // ---- permits ------------------------------------------------------------

    /** Queues {@code job} under the same per-host and overall limits as {@link Crawler}. */
    private void withPermit(URI url, boolean internal, Job job) {
        String host = hostKey(url, internal);
        waiting.computeIfAbsent(host, k -> new ArrayDeque<>()).add(release -> {
            if (cancelled) {
                release.run();
                taskDone();
                return;
            }
            job.run(release);
        });
        pump();
    }

    /** Starts queued jobs while permits are free. Re-entrant calls (a job finishing at once) loop here instead. */
    private void pump() {
        if (pumping) {
            pumpAgain = true;
            return;
        }
        pumping = true;
        try {
            do {
                pumpAgain = false;
                // A job may queue or finish others while it starts, so walk a copy of the hosts.
                for (String host : new ArrayList<>(waiting.keySet())) {
                    ArrayDeque<Job> queue;
                    while (active < options.maxInFlight()
                            && activePerHost.getOrDefault(host, 0) < options.perHost()
                            && (queue = waiting.get(host)) != null) {
                        Job job = queue.poll();
                        if (queue.isEmpty()) {
                            waiting.remove(host);
                        }
                        active++;
                        activePerHost.merge(host, 1, Integer::sum);
                        job.run(releaseOnce(host));
                    }
                }
            } while (pumpAgain && !cancelled);
        } finally {
            pumping = false;
        }
    }

    private Runnable releaseOnce(String host) {
        boolean[] released = {false};
        return () -> {
            if (released[0]) {
                return;
            }
            released[0] = true;
            active--;
            activePerHost.merge(host, -1, Integer::sum);
            pump();
        };
    }

    /** Each crawled site (with and without www.) shares one limit; other hosts get their own. */
    private static String hostKey(URI url, boolean internal) {
        return (internal ? Links.siteHost(url) : url.getHost()) + ":" + url.getPort();
    }

    // ---- checks -------------------------------------------------------------

    /**
     * As {@link Crawler}'s: GETs a URL and, when {@code parse} and it is an HTML page,
     * extracts its links; when it is an XML sitemap on a crawled site, the URLs it lists. A
     * start URL that redirects to another site makes that site a crawled one.
     */
    private void fetchPage(URI url, boolean internal, boolean parse, boolean start, Consumer<Check> done) {
        sendWithRetry(new Request(url, "GET", parse), response -> {
            if (response.kind() != Kind.RECEIVED) {
                done.accept(unreceived(response));
                return;
            }
            Check check = Check.of(response.status());
            URI finalUri = response.finalUri();
            if (start && check.outcome() == LinkResult.Outcome.OK) {
                adoptRedirectTarget(finalUri);
            }
            boolean html = isHtml(response.contentType());
            boolean followable = parse
                    && check.outcome() == LinkResult.Outcome.OK
                    && (html || (internal && isXml(response.contentType())))
                    && (!internal || Links.inSites(sites, finalUri))
                    && notYetSeenRedirectTarget(url, finalUri)
                    && stats.pages() < options.maxPages();
            if (!followable) {
                done.accept(check);
                return;
            }
            String body = truncate(response.body(), MAX_PAGE_BYTES);
            Check parsed;
            try {
                if (html) {
                    stats.pages.incrementAndGet();
                    Document doc = Jsoup.parse(body, finalUri.toString());
                    parsed = check.withLinks(new ArrayList<>(Links.extract(doc)));
                } else {
                    Document xml = Jsoup.parse(body, finalUri.toString(), Parser.xmlParser());
                    parsed = check.withListedLinks(new ArrayList<>(Links.extractSitemap(xml)));
                }
            } catch (RuntimeException e) {
                // The status is known; a page that cannot be parsed only has no links to follow.
                parsed = check;
            }
            done.accept(parsed);
        });
    }

    /** As in {@link Crawler}: a start URL's redirect to another site makes it a crawled site. */
    private void adoptRedirectTarget(URI finalUri) {
        Optional<URI> site = CheckRules.siteOf(finalUri);
        if (site.isPresent() && !Links.inSites(sites, finalUri)) {
            sites.add(site.get());
            if (options.sitemaps()) {
                discoverSitemaps(site.get());
            }
        }
    }

    /** As in {@link Crawler}: a redirect to an already crawled page is not parsed twice. */
    private boolean notYetSeenRedirectTarget(URI requested, URI finalUri) {
        URI target = Links.normalize(finalUri).orElse(requested);
        return target.equals(requested) || seen.add(target);
    }

    /** HEAD, falling back to GET when HEAD fails or is answered with an error status. */
    private void checkExternal(URI url, Consumer<Check> done) {
        sendWithRetry(new Request(url, "HEAD", false), head -> {
            if (head.kind() == Kind.HIDDEN || head.kind() == Kind.UNVERIFIED) {
                // A GET would fare no better.
                done.accept(unreceived(head));
                return;
            }
            if (head.kind() == Kind.RECEIVED && head.status() < 400) {
                done.accept(Check.of(head.status()));
                return;
            }
            sendWithRetry(new Request(url, "GET", false), get -> done.accept(
                    get.kind() == Kind.RECEIVED ? Check.of(get.status()) : unreceived(get)));
        });
    }

    /** One retry after a failure or a transient status, as in {@link Crawler}. */
    private void sendWithRetry(Request request, Consumer<Response> callback) {
        send(request, 1, callback);
    }

    private void send(Request request, int attempt, Consumer<Response> callback) {
        if (cancelled) {
            callback.accept(Response.failed(request.url(), "cancelled"));
            return;
        }
        transport.send(request, response -> {
            stats.requests.addAndGet(response.hops());
            if (attempt == 1 && !cancelled) {
                if (response.kind() == Kind.FAILED) {
                    transport.schedule(ERROR_RETRY_MILLIS, () -> send(request, 2, callback));
                    return;
                }
                if (response.kind() == Kind.RECEIVED && RETRY_STATUSES.contains(response.status())) {
                    transport.schedule(retryDelayMillis(response.retryAfter()), () -> send(request, 2, callback));
                    return;
                }
            }
            callback.accept(response);
        });
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
