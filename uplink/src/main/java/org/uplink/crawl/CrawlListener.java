package org.uplink.crawl;

import java.net.URI;

/** Receives crawl progress. Callbacks arrive concurrently from crawler threads. */
public interface CrawlListener {

    /** A request for {@code url} is about to be sent. */
    default void onCheckStarted(URI url, boolean internal) {
    }

    /** {@code url} has been checked. */
    default void onResult(LinkResult result) {
    }
}
