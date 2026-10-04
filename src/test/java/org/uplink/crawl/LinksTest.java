package org.uplink.crawl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class LinksTest {

    @Test
    void parseStartUrlAcceptsOnlyAbsoluteHttp() {
        assertEquals(Optional.of(URI.create("https://aletyx.ai/")), Links.parseStartUrl("https://aletyx.ai"));
        assertTrue(Links.parseStartUrl("aletyx.ai").isEmpty());
        assertTrue(Links.parseStartUrl("ftp://example.com/").isEmpty());
        assertTrue(Links.parseStartUrl("--help").isEmpty());
        assertTrue(Links.parseStartUrl(null).isEmpty());
    }

    @Test
    void normalizeDropsFragmentDefaultPortAndCase() {
        assertEquals("https://example.com/a?b=1",
                Links.normalize(URI.create("HTTPS://Example.COM:443/a?b=1#top")).orElseThrow().toString());
        assertEquals("http://example.com:8080/",
                Links.normalize(URI.create("http://example.com:8080")).orElseThrow().toString());
        assertTrue(Links.normalize(URI.create("mailto:someone@example.com")).isEmpty());
    }

    @Test
    void resolveToleratesUnescapedCharacters() {
        assertEquals("https://example.com/a%20b", Links.resolve("https://example.com/a b").orElseThrow().toString());
    }

    @Test
    void sameSiteIgnoresWwwButNotOtherSubdomains() {
        URI root = URI.create("https://aletyx.ai/");
        assertTrue(Links.sameSite(root, URI.create("https://www.aletyx.ai/x")));
        assertTrue(Links.sameSite(root, URI.create("http://aletyx.ai/x")));
        assertFalse(Links.sameSite(root, URI.create("https://docs.aletyx.ai/")));
        assertFalse(Links.sameSite(root, URI.create("https://example.com/")));
    }

    @Test
    void extractResolvesRelativeLinksAndSkipsNonHttp() {
        String html = """
                <html><head>
                  <link rel="stylesheet" href="/style.css">
                  <link rel="preconnect" href="https://fonts.gstatic.com">
                </head><body>
                  <a href="/about#team">About</a>
                  <a href="/about">About again</a>
                  <a href="contact">Contact</a>
                  <a href="#top">Top</a>
                  <a href="mailto:hi@example.com">Mail</a>
                  <a href="javascript:void(0)">JS</a>
                  <a href="tel:+123">Call</a>
                  <a href="/cdn-cgi/l/email-protection#6a0b0e">Obfuscated mail</a>
                  <a href="https://other.example/page">Other</a>
                  <img src="/logo.png">
                </body></html>
                """;
        List<String> links = Links.extract(Jsoup.parse(html, "https://site.example/dir/index.html"))
                .stream().map(URI::toString).toList();
        assertEquals(List.of(
                "https://site.example/style.css",
                "https://site.example/about",
                "https://site.example/dir/contact",
                "https://site.example/dir/index.html",
                "https://other.example/page",
                "https://site.example/logo.png"), links);
    }
}
