package org.uplink.web;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.function.Consumer;

import org.uplink.crawl.Crawler;
import org.uplink.crawl.Links;

/**
 * uplink in the browser, compiled to WebAssembly by GraalVM Web Image. {@code main}
 * publishes {@code uplinkStart} and {@code uplinkStop} for app.js and redraws the page
 * from the running {@link Session} a few times a second. Everything runs on the
 * browser's event loop; there is no server.
 */
public final class Main {

    /** How often the page is redrawn, as the TUI's tick rate. */
    private static final int RENDER_MILLIS = 250;

    private static Session session;
    private static String error;

    private Main() {
    }

    public static void main(String[] args) {
        // Event and pass times are shown in the browser's time zone, as the TUI shows local time.
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.ofTotalSeconds(Browser.utcOffsetMinutes() * 60)));
        Browser.exportApi(
                params -> guard(Main::start, Browser.string(params)),
                ignored -> guard(p -> stop(), ""));
        render();
        tick();
    }

    private static void tick() {
        Browser.setTimeout(RENDER_MILLIS, () -> {
            guard(p -> {
                // Nothing changes on the page once stopped, until the next start.
                if (session != null && !session.stopped()) {
                    render();
                }
            }, "");
            tick();
        });
    }

    private static void render() {
        Browser.render(session == null ? View.idle(error) : View.of(session, error));
    }

    /**
     * Starts checking, replacing any running session. {@code params} is the form as a
     * query string: {@code sites}, {@code concurrency}, {@code maxInFlight},
     * {@code timeout}, {@code maxPages}, {@code interval}, {@code slow} and {@code sitemaps}.
     */
    static void start(String params) {
        stop();
        error = null;
        Map<String, String> form = parse(params);
        Optional<List<URI>> sites = Links.parseSites(form.getOrDefault("sites", "").trim());
        if (sites.isEmpty()) {
            error = "Enter an http(s) URL, optionally followed by more sites separated by , or ;";
            session = null;
            render();
            return;
        }
        try {
            int concurrency = positive(form, "concurrency", 8);
            int maxInFlight = positive(form, "maxInFlight", 64);
            int timeout = positive(form, "timeout", 20);
            int maxPages = positive(form, "maxPages", 10_000);
            int slow = positive(form, "slow", 1000);
            int interval = number(form, "interval", 30);
            if (interval < 0) {
                throw new IllegalArgumentException("interval must not be negative");
            }
            boolean sitemaps = form.containsKey("sitemaps");
            // The browser follows redirects and sets its own User-Agent.
            Crawler.Options options = new Crawler.Options(concurrency, maxInFlight, Duration.ofSeconds(timeout),
                    maxPages, Crawler.Options.DEFAULT_USER_AGENT, true, sitemaps);
            session = new Session(sites.get(), options, Duration.ofSeconds(interval), Duration.ofMillis(slow));
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
            session = null;
            render();
            return;
        }
        session.start();
        render();
    }

    static void stop() {
        if (session != null && !session.stopped()) {
            session.stop();
            render();
        }
    }

    private static Map<String, String> parse(String query) {
        Map<String, String> form = new HashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            form.put(key, value);
        }
        return form;
    }

    private static int number(Map<String, String> form, String key, int fallback) {
        String raw = form.getOrDefault(key, "").trim();
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number, got: " + raw);
        }
    }

    private static int positive(Map<String, String> form, String key, int fallback) {
        int n = number(form, key, fallback);
        if (n < 1) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return n;
    }

    /**
     * Runs a callback from JavaScript, logging what it throws: an exception escaping
     * into the browser would leave the crawl waiting for a callback that never ends.
     */
    static <T> void guard(Consumer<T> action, T value) {
        try {
            action.accept(value);
        } catch (Throwable t) {
            Browser.logError("uplink: " + t);
            error = "Internal error: " + t;
        }
    }
}
