package org.uplink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.uplink.report.ReportLog;

import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

@QuarkusMainTest
class UplinkCommandTest {

    @Test
    void consoleModeReportsBrokenLinksAndFails(QuarkusMainLauncher launcher) throws Exception {
        try (TestSite site = new TestSite()) {
            LaunchResult result = launcher.launch("--mode=console", "--summary-interval=1", site.siteUrl());
            String out = result.getOutput();
            assertEquals(1, result.exitCode(), out);
            assertTrue(out.contains("uplink report for " + site.siteUrl()), out);
            assertTrue(out.contains("Pages crawled:     3"), out);
            // The crawl's 16 requests plus robots.txt, read for sitemaps (there is none).
            assertTrue(out.contains("Requests sent:     17"), out);
            assertTrue(out.contains("| pages 3 | requests 17 |"), out);
            assertTrue(out.contains("Good links:        8"), out);
            assertTrue(out.contains("Broken links:      4"), out);
            assertTrue(out.contains("Unverified links:  2"), out);
            assertTrue(out.contains("[404 Not Found] " + site.siteUrl() + "missing"), out);
            assertTrue(out.contains("found on " + site.siteUrl() + "b"), out);
            assertTrue(out.contains("BROKEN     [500 Internal Server Error]"), out);

            String log = Files.readString(ReportLog.DEFAULT);
            assertTrue(log.startsWith("\nuplink report for " + site.siteUrl()), log);
            assertTrue(log.contains("[404 Not Found] " + site.siteUrl() + "missing"), log);
            assertTrue(!log.contains("progress:") && !log.contains("done:"), "only the final report: " + log);
            // Bad links to other sites are counted, not listed.
            assertTrue(!log.contains("/ext/gone") && !log.contains("/local-only"), log);
            assertTrue(log.contains("Broken links (4):"), log);
            assertTrue(log.contains("  1 external link (not listed)"), log);
        }
    }

    @Test
    void healthySiteExitsZero(QuarkusMainLauncher launcher) throws Exception {
        try (TestSite site = new TestSite()) {
            LaunchResult result = launcher.launch("--mode=console", site.externalUrl("/ext/ok"));
            assertEquals(0, result.exitCode(), result.getOutput());
            assertTrue(result.getOutput().contains("No bad links found."), result.getOutput());
        }
    }

    @Test
    void firstArgumentListsMoreSitesToCrawl(QuarkusMainLauncher launcher) throws Exception {
        try (TestSite site = new TestSite()) {
            LaunchResult result = launcher.launch("--mode=console", site.siteUrl() + ";" + site.externalUrl("/"));
            assertTrue(result.getOutput().contains("also crawling [" + site.externalUrl("/") + "]"), result.getOutput());
            assertEquals(1, site.hits("/ext/deep"), result.getOutput());
        }
    }

    @Test
    void firstArgumentMustBeUrl(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("not-a-url");
        assertEquals(2, result.exitCode());
        assertTrue(result.getErrorOutput().contains("must be an http(s) URL"), result.getErrorOutput());
    }
}
