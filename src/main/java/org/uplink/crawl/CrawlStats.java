package org.uplink.crawl;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Live counters of a crawl, safe to read from any thread. */
public final class CrawlStats {

    final AtomicLong discovered = new AtomicLong();
    final AtomicLong checked = new AtomicLong();
    final AtomicLong ok = new AtomicLong();
    final AtomicLong broken = new AtomicLong();
    final AtomicLong blocked = new AtomicLong();
    final AtomicLong inFlight = new AtomicLong();
    final AtomicLong pages = new AtomicLong();
    private final long startNanos = System.nanoTime();
    private volatile long endNanos;

    void record(LinkResult result) {
        checked.incrementAndGet();
        switch (result.outcome()) {
            case OK -> ok.incrementAndGet();
            case BROKEN -> broken.incrementAndGet();
            case BLOCKED -> blocked.incrementAndGet();
        }
    }

    void finish() {
        if (endNanos == 0) {
            endNanos = System.nanoTime();
        }
    }

    /** Unique links found so far (checked, in flight or waiting). */
    public long discovered() {
        return discovered.get();
    }

    public long checked() {
        return checked.get();
    }

    public long ok() {
        return ok.get();
    }

    public long broken() {
        return broken.get();
    }

    public long blocked() {
        return blocked.get();
    }

    public long inFlight() {
        return inFlight.get();
    }

    /** Links discovered but not yet being checked. */
    public long queued() {
        return Math.max(0, discovered() - checked() - inFlight());
    }

    /** Internal HTML pages parsed for links. */
    public long pages() {
        return pages.get();
    }

    public boolean finished() {
        return endNanos != 0;
    }

    public Duration elapsed() {
        long end = endNanos != 0 ? endNanos : System.nanoTime();
        return Duration.ofNanos(end - startNanos);
    }
}
