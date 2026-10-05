package org.uplink.web;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

import org.uplink.crawl.AsyncCrawler;

/** Sends {@link AsyncCrawler} requests with the browser's {@code fetch}. */
final class BrowserTransport implements AsyncCrawler.Transport {

    private final int timeoutMillis;

    BrowserTransport(Duration timeout) {
        this.timeoutMillis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
    }

    @Override
    public void send(AsyncCrawler.Request request, Consumer<AsyncCrawler.Response> callback) {
        URI url = request.url();
        Browser.fetch(url.toString(), request.method(), request.readBody(), timeoutMillis, value -> {
            AsyncCrawler.Response response;
            try {
                response = decode(url, Browser.string(value));
            } catch (RuntimeException e) {
                response = AsyncCrawler.Response.failed(url, "unreadable response: " + e.getMessage());
            }
            Main.guard(callback, response);
        });
    }

    @Override
    public void schedule(long delayMillis, Runnable task) {
        Browser.setTimeout((int) Math.min(Integer.MAX_VALUE, delayMillis), () -> Main.guard(t -> t.run(), task));
    }

    /** Decodes {@link Browser#fetch}'s reply. */
    static AsyncCrawler.Response decode(URI url, String reply) {
        String[] f = reply.split("\n", 8);
        String kind = f[0];
        String detail = f[6];
        return switch (kind) {
            case "received" -> {
                URI finalUri = f[2].isEmpty() ? url : URI.create(f[2]);
                // fetch() hides the redirect chain: a redirected response took at least one more request.
                int hops = f[4].equals("1") ? 2 : 1;
                yield AsyncCrawler.Response.received(Integer.parseInt(f[1]), finalUri, f[3], f.length > 7 ? f[7] : "",
                        hops, f[5].isEmpty() ? Optional.empty() : Optional.of(f[5]));
            }
            case "hidden" -> AsyncCrawler.Response.hidden(url);
            case "unverified" -> AsyncCrawler.Response.unverified(url, detail);
            default -> AsyncCrawler.Response.failed(url, detail.isEmpty() ? "network error" : detail);
        };
    }
}
