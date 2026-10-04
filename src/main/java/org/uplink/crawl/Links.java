package org.uplink.crawl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** URL normalization, site membership and link extraction helpers. */
public final class Links {

    /** Elements whose attribute points at another resource that should resolve. */
    private static final String LINK_SELECTOR = "a[href], area[href], link[href], img[src], script[src], "
            + "iframe[src], source[src], video[src], audio[src], embed[src], track[src]";

    /** {@code <link rel=...>} values whose href is a hint (an origin), not a fetchable resource. */
    private static final Set<String> HINT_RELS = Set.of("preconnect", "dns-prefetch");

    /**
     * Cloudflare's email obfuscation rewrites {@code mailto:} links to this path and
     * decodes them with JavaScript in the browser; the URL itself always 404s.
     */
    private static final String CLOUDFLARE_EMAIL_PROTECTION = "/cdn-cgi/l/email-protection";

    private Links() {
    }

    /**
     * Parses a user-supplied start URL. Only absolute http(s) URLs with a host qualify.
     */
    public static Optional<URI> parseStartUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(raw.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return Optional.empty();
            }
            return normalize(uri);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * Normalizes a URL so equivalent links are only checked once: lowercase scheme and
     * host, no default port, no fragment, and {@code /} for an empty path.
     *
     * @return the normalized URI, or empty if it is not an http(s) URL with a host
     */
    public static Optional<URI> normalize(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            return Optional.empty();
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return Optional.empty();
        }
        int port = uri.getPort();
        if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
            port = -1;
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        StringBuilder sb = new StringBuilder(scheme).append("://").append(host.toLowerCase(Locale.ROOT));
        if (port != -1) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (uri.getRawQuery() != null) {
            sb.append('?').append(uri.getRawQuery());
        }
        try {
            return Optional.of(new URI(sb.toString()));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /** Resolves a possibly relative, possibly sloppy href found in a page. */
    public static Optional<URI> resolve(String absoluteHref) {
        if (absoluteHref == null || absoluteHref.isBlank()) {
            return Optional.empty();
        }
        String href = absoluteHref.trim();
        try {
            return normalize(new URI(href));
        } catch (URISyntaxException e) {
            // Browsers tolerate unescaped characters (spaces, pipes, unicode...);
            // let the multi-argument constructor quote them.
            try {
                URI lenient = toLenientUri(href);
                return lenient == null ? Optional.empty() : normalize(lenient);
            } catch (URISyntaxException | IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
    }

    private static URI toLenientUri(String href) throws URISyntaxException {
        int schemeEnd = href.indexOf("://");
        if (schemeEnd < 0) {
            return null;
        }
        String scheme = href.substring(0, schemeEnd);
        String rest = href.substring(schemeEnd + 3);
        int fragment = rest.indexOf('#');
        if (fragment >= 0) {
            rest = rest.substring(0, fragment);
        }
        String query = null;
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        int slash = rest.indexOf('/');
        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
        String path = slash >= 0 ? rest.substring(slash) : "/";
        return new URI(scheme, authority, path, query, null);
    }

    /**
     * Whether {@code candidate} belongs to the site rooted at {@code root}. Hosts are
     * compared ignoring a leading {@code www.}, so {@code example.com} and
     * {@code www.example.com} are the same site; other subdomains are external.
     */
    public static boolean sameSite(URI root, URI candidate) {
        if (root.getHost() == null || candidate.getHost() == null) {
            return false;
        }
        return siteHost(root).equals(siteHost(candidate)) && root.getPort() == candidate.getPort();
    }

    private static String siteHost(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    /**
     * Extracts every checkable link of a parsed page. Relative links are resolved
     * against the document's base URI (honouring {@code <base href>}).
     */
    public static Set<URI> extract(Document doc) {
        Set<URI> links = new LinkedHashSet<>();
        for (Element el : doc.select(LINK_SELECTOR)) {
            if (el.normalName().equals("link") && HINT_RELS.contains(el.attr("rel").toLowerCase(Locale.ROOT).trim())) {
                continue;
            }
            String attr = el.hasAttr("href") ? "href" : "src";
            resolve(el.absUrl(attr))
                    .filter(uri -> !CLOUDFLARE_EMAIL_PROTECTION.equals(uri.getRawPath()))
                    .ifPresent(links::add);
        }
        return links;
    }
}
