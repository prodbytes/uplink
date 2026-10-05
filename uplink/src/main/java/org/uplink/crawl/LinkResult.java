package org.uplink.crawl;

import java.net.URI;
import java.time.Duration;

/**
 * Outcome of checking a single link.
 *
 * @param url      the normalized link that was checked
 * @param referrer the page where the link was first found ({@code null} for the start URL)
 * @param internal whether the link belongs to the crawled site (and was crawled recursively)
 * @param status   final HTTP status, or 0 when no response was received
 * @param outcome  classification of the result
 * @param detail   human-readable reason (status text or error)
 * @param elapsed  time spent checking the link
 */
public record LinkResult(URI url, URI referrer, boolean internal, int status, Outcome outcome,
        String detail, Duration elapsed) {

    public enum Outcome {
        /** The link resolved (2xx, or a redirect that was not followed). */
        OK,
        /** The link is broken: 4xx/5xx, DNS failure, refused connection, timeout, TLS error... */
        BROKEN,
        /**
         * The server refused automated access (401, 403, 429, LinkedIn's 999). The
         * link most likely exists but could not be verified.
         */
        BLOCKED
    }

    public boolean isBad() {
        return outcome != Outcome.OK;
    }

    /** Short status label such as {@code 404} or {@code ERR}. */
    public String statusLabel() {
        return status > 0 ? Integer.toString(status) : "ERR";
    }
}
