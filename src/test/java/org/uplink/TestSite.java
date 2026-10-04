package org.uplink;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A small website served on the loopback interface. The crawled site is addressed as
 * {@code localhost}; "external" links use {@code external.test}, which the test JVM
 * resolves to the same server (see {@code src/test/hosts}), so the crawler must check
 * them without following them. Loopback addresses cannot be used for this, because
 * links to them are never requested.
 */
public final class TestSite implements AutoCloseable {

    private static final String EXTERNAL_HOST = "external.test";

    private final HttpServer server;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final Map<Boolean, AtomicInteger> active = Map.of(true, new AtomicInteger(), false, new AtomicInteger());
    private final Map<Boolean, AtomicInteger> peak = Map.of(true, new AtomicInteger(), false, new AtomicInteger());
    private final AtomicInteger activeTotal = new AtomicInteger();
    private final AtomicInteger peakTotal = new AtomicInteger();

    public TestSite() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String siteUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/";
    }

    public String externalUrl(String path) {
        return "http://" + EXTERNAL_HOST + ":" + server.getAddress().getPort() + path;
    }

    /** The site on a loopback address that is not the site's own host. */
    public String localUrl(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** Requests received for a path, any host and method. */
    public int hits(String path) {
        AtomicInteger n = hits.get(path);
        return n == null ? 0 : n.get();
    }

    /** Highest number of requests served at the same time for the site or the "external" host. */
    public int peakConcurrency(boolean external) {
        return peak.get(external).get();
    }

    /** Highest number of requests served at the same time, any host. */
    public int peakConcurrency() {
        return peakTotal.get();
    }

    private void handle(HttpExchange ex) throws IOException {
        String host = ex.getRequestHeaders().getFirst("Host");
        boolean external = host != null && host.startsWith(EXTERNAL_HOST);
        peak.get(external).accumulateAndGet(active.get(external).incrementAndGet(), Math::max);
        peakTotal.accumulateAndGet(activeTotal.incrementAndGet(), Math::max);
        try {
            serve(ex, external);
        } finally {
            active.get(external).decrementAndGet();
            activeTotal.decrementAndGet();
        }
    }

    private void serve(HttpExchange ex, boolean external) throws IOException {
        String path = ex.getRequestURI().getPath();
        hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
        if (path.startsWith("/slow/")) {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            html(ex, "slow");
            return;
        }
        String ext = externalUrl("");
        switch (external ? "ext:" + path : path) {
            case "/" -> html(ex, """
                    <a href="/a">A</a>
                    <a href="/a#section">A again</a>
                    <a href="/missing">Missing</a>
                    <a href="/doc.pdf">PDF</a>
                    <a href="/redirect">Redirect</a>
                    <a href="mailto:test@example.com">Mail</a>
                    <a href="%1$s/ext/ok">External OK</a>
                    <a href="%1$s/ext/gone">External gone</a>
                    <a href="%1$s/ext/page">External page</a>
                    <a href="%1$s/ext/no-head">External without HEAD</a>
                    <a href="%2$s">Local only</a>
                    """.formatted(ext, localUrl("/local-only")));
            case "/fanout" -> {
                StringBuilder links = new StringBuilder();
                for (int i = 0; i < 12; i++) {
                    links.append("<a href=\"/slow/").append(i).append("\">s</a>")
                            .append("<a href=\"").append(ext).append("/slow/").append(i).append("\">e</a>");
                }
                html(ex, links.toString());
            }
            case "/a" -> html(ex, "<a href=\"/\">home</a> <a href=\"b\">B</a>");
            case "/b" -> html(ex, "<a href=\"/forbidden\">F</a> <a href=\"/server-error\">E</a> <img src=\"/img/missing.png\">");
            case "/doc.pdf" -> respond(ex, 200, "application/pdf", "%PDF-1.4 <a href=\"/never-followed\">");
            case "/redirect" -> {
                ex.getResponseHeaders().add("Location", "/a");
                respond(ex, 301, "text/plain", "");
            }
            case "/forbidden" -> respond(ex, 403, "text/plain", "no robots");
            case "/server-error" -> respond(ex, 500, "text/plain", "boom");
            case "ext:/ext/ok" -> html(ex, "fine");
            case "ext:/ext/page" -> html(ex, "<a href=\"/ext/deep\">must not be crawled</a>");
            case "ext:/ext/no-head" -> {
                if (ex.getRequestMethod().equals("HEAD")) {
                    respond(ex, 405, "text/plain", "");
                } else {
                    html(ex, "GET works");
                }
            }
            default -> respond(ex, 404, "text/plain", "not found");
        }
    }

    private static void html(HttpExchange ex, String body) throws IOException {
        respond(ex, 200, "text/html; charset=utf-8", "<html><body>" + body + "</body></html>");
    }

    private static void respond(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        boolean noBody = ex.getRequestMethod().equals("HEAD") || bytes.length == 0;
        ex.sendResponseHeaders(status, noBody ? -1 : bytes.length);
        if (!noBody) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
