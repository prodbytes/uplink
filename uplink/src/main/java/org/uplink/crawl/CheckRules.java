package org.uplink.crawl;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * How a response is judged, shared by {@link Crawler} and {@link AsyncCrawler} so both
 * classify links, retry and discover sitemaps the same way.
 */
final class CheckRules {

    /** Largest HTML body parsed for links; anything beyond is ignored. */
    static final int MAX_PAGE_BYTES = 10 * 1024 * 1024;

    /** Largest robots.txt read for {@code Sitemap:} lines. */
    static final int MAX_ROBOTS_BYTES = 512 * 1024;

    /** Statuses meaning "the server refused the robot", not "the page is missing". */
    static final Set<Integer> BLOCKED_STATUSES = Set.of(401, 403, 429, 999);

    /** Transient statuses worth one more attempt. */
    static final Set<Integer> RETRY_STATUSES = Set.of(429, 502, 503, 504);

    /** Wait before retrying after a network error. */
    static final long ERROR_RETRY_MILLIS = 500;

    private static final Map<Integer, String> REASONS = Map.ofEntries(
            Map.entry(400, "Bad Request"), Map.entry(401, "Unauthorized"), Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"), Map.entry(405, "Method Not Allowed"), Map.entry(408, "Request Timeout"),
            Map.entry(410, "Gone"), Map.entry(429, "Too Many Requests"), Map.entry(451, "Unavailable For Legal Reasons"),
            Map.entry(500, "Internal Server Error"), Map.entry(501, "Not Implemented"), Map.entry(502, "Bad Gateway"),
            Map.entry(503, "Service Unavailable"), Map.entry(504, "Gateway Timeout"),
            Map.entry(999, "Request Denied"));

    private CheckRules() {
    }

    /**
     * @param links  the links found on the page
     * @param listed whether {@code links} come from a sitemap, which lists pages rather
     *               than linking to them: they stay at the sitemap's depth
     */
    record Check(int status, LinkResult.Outcome outcome, String detail, List<URI> links, boolean listed) {

        /** A link to localhost outside the crawled sites: visitors cannot open it, and it is never requested. */
        static final Check LOCAL = new Check(0, LinkResult.Outcome.BLOCKED, "links to localhost", List.of(), false);

        Check(int status, LinkResult.Outcome outcome, String detail, List<URI> links) {
            this(status, outcome, detail, links, false);
        }

        static Check of(int status) {
            LinkResult.Outcome outcome = status < 400 ? LinkResult.Outcome.OK
                    : BLOCKED_STATUSES.contains(status) ? LinkResult.Outcome.BLOCKED
                    : LinkResult.Outcome.BROKEN;
            return new Check(status, outcome, describe(status), List.of());
        }

        static Check failure(String detail) {
            return new Check(0, LinkResult.Outcome.BROKEN, detail, List.of());
        }

        Check withLinks(List<URI> links) {
            return new Check(status, outcome, detail, links, false);
        }

        Check withListedLinks(List<URI> links) {
            return new Check(status, outcome, detail, links, true);
        }
    }

    /**
     * Where a link sits in the crawl.
     *
     * @param hops    links followed from a start URL (a start URL, or a page a sitemap lists, is 0)
     * @param offsite consecutive links followed off the crawled sites to get here: 0 for a
     *                page on them, 1 for a page they link to on another site, and so on
     */
    record Depth(int hops, int offsite) {

        static final Depth START = new Depth(0, 0);

        /** A link on this page. */
        Depth child(boolean internal) {
            return new Depth(hops + 1, internal ? 0 : offsite + 1);
        }

        /** A page a sitemap at this depth lists. */
        Depth listed(boolean internal) {
            return new Depth(hops, internal ? 0 : offsite + 1);
        }

        /**
         * Whether a page here is parsed for links: on the crawled sites up to
         * {@link Crawler.Options#maxDepth()} links from a start URL, elsewhere up to
         * {@link Crawler.Options#maxExternalDepth()} links off them.
         */
        boolean crawled(Crawler.Options options) {
            return offsite == 0 ? hops <= options.maxDepth() : offsite <= options.maxExternalDepth();
        }
    }

    /** The site root ({@code scheme://host[:port]/}) of {@code url}. */
    static Optional<URI> siteOf(URI url) {
        return Links.normalize(url.resolve("/"));
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

    static boolean isHtml(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return type.contains("text/html") || type.contains("application/xhtml");
    }

    static boolean isXml(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return type.contains("/xml") || type.contains("+xml");
    }

    /** Charset from Content-Type, or {@code null} to let jsoup sniff it from the document. */
    static String charset(String contentType) {
        String type = contentType == null ? "" : contentType;
        for (String part : type.split(";")) {
            String p = part.trim();
            if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                String cs = p.substring(8).replace("\"", "").trim();
                return cs.isEmpty() ? null : cs;
            }
        }
        return null;
    }

    /** The {@code Sitemap:} URLs in a robots.txt that are on a crawled site. */
    static List<URI> sitemapUrls(String robotsTxt, List<URI> sites) {
        List<URI> sitemaps = new ArrayList<>();
        for (String line : robotsTxt.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("sitemap")) {
                Links.resolve(line.substring(colon + 1).trim())
                        .filter(uri -> Links.inSites(sites, uri))
                        .ifPresent(sitemaps::add);
            }
        }
        return sitemaps;
    }

    /** Wait before retrying a transient status: Retry-After seconds (at most 10), else 2s. */
    static long retryDelayMillis(Optional<String> retryAfter) {
        return retryAfter
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
}
