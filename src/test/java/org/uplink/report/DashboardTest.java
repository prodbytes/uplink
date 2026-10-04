package org.uplink.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.uplink.TestSite;
import org.uplink.crawl.Crawler;
import org.uplink.crawl.LinkResult;
import org.uplink.crawl.LinkResult.Outcome;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.RenderThread;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

class DashboardTest {

    private static final URI ROOT = URI.create("https://site.example/");

    /** Rendering checks that it runs on the TUI's render thread; let the test thread act as one. */
    @BeforeEach
    void actAsRenderThread() throws ReflectiveOperationException {
        var mark = RenderThread.class.getDeclaredMethod("markAsRenderThread");
        mark.setAccessible(true);
        mark.invoke(null);
    }

    private static Dashboard dashboard() {
        Dashboard dashboard = new Dashboard(ROOT, Crawler.Options.defaults(), Duration.ofMinutes(1), Duration.ofSeconds(1));
        Monitor monitor = dashboard.monitor();
        monitor.onResult(new LinkResult(ROOT, null, true, 200, Outcome.OK, "OK", Duration.ofMillis(80)));
        monitor.onResult(new LinkResult(ROOT.resolve("/docs"), ROOT, true, 200, Outcome.OK, "OK", Duration.ofMillis(1500)));
        monitor.onResult(new LinkResult(ROOT.resolve("/blog"), ROOT, true, 200, Outcome.OK, "OK", Duration.ofMillis(300)));
        monitor.onResult(new LinkResult(ROOT.resolve("/gone"), ROOT, true, 404, Outcome.BROKEN, "Not Found", Duration.ofMillis(40)));
        monitor.onResult(new LinkResult(URI.create("https://other.example/x"), ROOT, false, 0, Outcome.BROKEN,
                "connection refused", Duration.ofMillis(10)));
        monitor.onResult(new LinkResult(URI.create("https://social.example/"), ROOT, false, 999, Outcome.BLOCKED,
                "blocked", Duration.ofMillis(20)));
        return dashboard;
    }

    private static String screen(Dashboard dashboard) {
        Buffer buffer = Buffer.empty(Rect.of(140, 32));
        dashboard.render().render(Frame.forTesting(buffer), buffer.area(), RenderContext.empty());
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < buffer.height(); y++) {
            for (int x = 0; x < buffer.width(); x++) {
                text.append(buffer.get(x, y).symbol());
            }
            text.append('\n');
        }
        if (Boolean.getBoolean("uplink.printScreens")) {
            System.out.println(text);
        }
        return text.toString();
    }

    @Test
    void switchesTabsWithNumbersAndArrows() {
        Dashboard dashboard = dashboard();
        assertEquals(Dashboard.Tab.SUMMARY, dashboard.tab());
        assertTrue(screen(dashboard).contains("Events"));

        dashboard.handle(KeyEvent.ofChar('2'));
        assertEquals(Dashboard.Tab.BROKEN, dashboard.tab());
        String broken = screen(dashboard);
        assertTrue(broken.contains("2 broken, 1 unverified"), broken);
        assertTrue(broken.contains("1 × 404"), broken);
        assertTrue(broken.contains("https://other.example/x"), broken);

        dashboard.handle(KeyEvent.ofKey(KeyCode.RIGHT));
        assertEquals(Dashboard.Tab.LATENCY, dashboard.tab());
        String latency = screen(dashboard);
        assertTrue(latency.contains("4 pages, slowest first"), latency);
        assertTrue(latency.contains("Latency histogram"), latency);
        // Internal pages only, slowest first.
        assertTrue(latency.indexOf("/docs") < latency.indexOf("/blog"), latency);
        assertTrue(!latency.contains("other.example"), latency);

        assertEquals(EventResult.HANDLED, dashboard.handle(KeyEvent.ofKey(KeyCode.DOWN)));
        String down = screen(dashboard);
        assertTrue(down.contains("▶     300ms 200 https://site.example/blog"), down);
        dashboard.handle(KeyEvent.ofKey(KeyCode.END)); // clamped to the last row when rendered
        String end = screen(dashboard);
        assertTrue(end.contains("▶      40ms 404 https://site.example/gone"), end);

        dashboard.handle(KeyEvent.ofKey(KeyCode.RIGHT));
        assertEquals(Dashboard.Tab.SUMMARY, dashboard.tab());
        dashboard.handle(KeyEvent.ofKey(KeyCode.LEFT));
        assertEquals(Dashboard.Tab.LATENCY, dashboard.tab());
    }

    @Test
    void headerShowsSlowThresholdNextToSlowCount() throws Exception {
        Dashboard dashboard = dashboard();
        try (TestSite site = new TestSite()) {
            Crawler crawler = new Crawler(URI.create(site.siteUrl()), Crawler.Options.defaults(), dashboard.monitor());
            crawler.run();
            dashboard.monitor().passFinished(1, crawler);
        }
        String summary = screen(dashboard);
        assertTrue(summary.contains(" slow (≥ 1000ms)"), summary);
    }

    @Test
    void histogramBucketsPageLatencies() {
        Dashboard dashboard = dashboard();
        var buckets = Monitor.histogram(dashboard.monitor().pageLatencies());
        assertEquals("<100ms", buckets.getFirst().label());
        assertEquals("≥5s", buckets.getLast().label());
        assertEquals(2, buckets.getFirst().count()); // 80ms and 40ms
        assertEquals(4, buckets.stream().mapToInt(Monitor.Bucket::count).sum());
    }

    @Test
    void percentileUsesNearestRank() {
        var pages = dashboard().monitor().pageLatencies(); // 1500, 300, 80, 40
        assertEquals(80, Dashboard.percentile(pages, 50));
        assertEquals(1500, Dashboard.percentile(pages, 95));
    }
}
