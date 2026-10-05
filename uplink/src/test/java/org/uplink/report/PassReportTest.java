package org.uplink.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.uplink.crawl.CrawlStats;
import org.uplink.crawl.LinkResult;
import org.uplink.crawl.LinkResult.Outcome;

class PassReportTest {

    private static final URI ROOT = URI.create("https://site.test/");
    private static final URI PAGE = URI.create("https://site.test/a");
    private static final URI GONE = URI.create("https://site.test/gone");
    private static final URI EXT = URI.create("https://ext.test/missing");

    @Test
    void listsEveryBadLinkWithItsPagesAndSummarizesTheRest() {
        List<LinkResult> results = List.of(
                new LinkResult(ROOT, null, true, 200, Outcome.OK, "OK", Duration.ofMillis(50)),
                new LinkResult(PAGE, ROOT, true, 200, Outcome.OK, "OK", Duration.ofMillis(1500)),
                new LinkResult(GONE, ROOT, true, 404, Outcome.BROKEN, "Not Found", Duration.ofMillis(20)),
                new LinkResult(EXT, PAGE, false, 410, Outcome.BROKEN, "Gone", Duration.ofMillis(30)),
                new LinkResult(URI.create("https://ext.test/login"), PAGE, false, 403, Outcome.BLOCKED, "Forbidden",
                        Duration.ofMillis(30)));
        Map<URI, PassReport.FoundOn> on = Map.of(
                GONE, new PassReport.FoundOn(List.of(ROOT, PAGE), 2),
                EXT, new PassReport.FoundOn(List.of(PAGE), 60));

        String text = PassReport.text(3, List.of(ROOT), new CrawlStats(), results,
                url -> on.getOrDefault(url, new PassReport.FoundOn(List.of(PAGE), 1)),
                Duration.ofSeconds(1), LocalDateTime.of(2026, 10, 5, 14, 3, 12));

        assertTrue(text.startsWith("uplink report, pass #3\n"), text);
        assertTrue(text.contains("Finished:  2026-10-05 14:03:12"), text);
        assertTrue(text.contains("Links checked:     5  (3 on the sites, 2 on other sites)"), text);
        assertTrue(text.contains("Good:              2\n"), text);
        assertTrue(text.contains("Broken:            2\n"), text);
        assertTrue(text.contains("Bad by status:     1 x 403, 1 x 404, 1 x 410"), text);
        assertTrue(text.contains("Broken links (2):\n  [410 Gone] https://ext.test/missing (external)\n"
                + "      found on 60 pages:\n        https://site.test/a\n        ... and 59 more\n"), text);
        assertTrue(text.contains("  [404 Not Found] https://site.test/gone\n      found on 2 pages:\n"
                + "        https://site.test/\n        https://site.test/a\n"), text);
        assertTrue(text.contains("Unverified links (1):\n  [403 Forbidden] https://ext.test/login (external)"), text);
        assertTrue(text.contains("Slow links (1), slowest first:\n    1500ms  https://site.test/a\n"), text);
        assertFalse(text.contains("No broken"), text);
    }
}
