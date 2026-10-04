package org.uplink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
            assertTrue(out.contains("Good links:        8"), out);
            assertTrue(out.contains("Broken links:      4"), out);
            assertTrue(out.contains("Unverified links:  1"), out);
            assertTrue(out.contains("[404 Not Found] " + site.siteUrl() + "missing"), out);
            assertTrue(out.contains("found on " + site.siteUrl() + "b"), out);
            assertTrue(out.contains("BROKEN     [500 Internal Server Error]"), out);
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
    void firstArgumentMustBeUrl(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("not-a-url");
        assertEquals(2, result.exitCode());
        assertTrue(result.getErrorOutput().contains("must be an http(s) URL"), result.getErrorOutput());
    }
}
