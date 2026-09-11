package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a <b>web read</b> hands the model: a page's title and its text, and nothing of the markup it arrived
 * in (#360). A page is read to answer a question, so the decisions here are about what the model can use —
 * which content types are text at all, what a script block is worth (nothing), and how much of a long page
 * is enough — and about saying when it was cut, exactly as a <b>Read-only command</b>'s outcome does.
 */
class WebPageTest {

    @Test
    void itKeepsTheTitleAndTheTextAndThrowsTheMarkupAway() {
        WebPage page = WebPage.fromHtml("https://example.com/a",
            "<html><head><title>AllowedIPs</title></head><body><h1>Quick start</h1>"
                + "<p>A peer's <b>AllowedIPs</b> is a routing table.</p></body></html>");

        assertThat(page.title()).isEqualTo("AllowedIPs");
        assertThat(page.text()).isEqualTo("AllowedIPs Quick start A peer's AllowedIPs is a routing table.");
        assertThat(page.url()).isEqualTo("https://example.com/a");
        assertThat(page.cut()).isFalse();
    }

    /** The title reads first, because a page's own name is the fastest way to know it is the right page. */
    @Test
    void theTitleComesFirstInTheText() {
        assertThat(WebPage.fromHtml("https://example.com/a",
            "<html><head><title>The name</title></head><body>the body</body></html>").text())
            .startsWith("The name");
    }

    /** A script is a page's machinery, not its words, and it is by far the biggest part of most pages. */
    @Test
    void itDropsScriptStyleNoscriptAndSvgWholesale() {
        WebPage page = WebPage.fromHtml("https://example.com/a",
            "<html><body>before"
                + "<script>var secret = 'do not read me';</script>"
                + "<style>body { color: red; }</style>"
                + "<noscript>turn javascript on</noscript>"
                + "<svg viewBox=\"0 0 1 1\"><path d=\"M0 0\"/></svg>"
                + "after</body></html>");

        assertThat(page.text()).isEqualTo("before after");
        assertThat(page.text()).doesNotContain("secret").doesNotContain("color")
            .doesNotContain("javascript").doesNotContain("path");
    }

    @Test
    void itDropsHtmlCommentsToo() {
        assertThat(WebPage.fromHtml("https://example.com/a", "<p>said<!-- unsaid --></p>").text())
            .isEqualTo("said");
    }

    @Test
    void itDecodesTheEntitiesAPageIsWrittenWith() {
        assertThat(WebPage.fromHtml("https://example.com/a",
            "<p>a&amp;b &lt;tag&gt; &quot;quoted&quot; it&#39;s&nbsp;here &#8212; &#x2764;</p>").text())
            .isEqualTo("a&b <tag> \"quoted\" it's here — ❤");
    }

    /** A decoded &amp;lt; must not then read as the start of a tag, so the markup goes before the entities. */
    @Test
    void aDecodedEntityIsNeverReadAsMarkup() {
        assertThat(WebPage.fromHtml("https://example.com/a", "<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>").text())
            .isEqualTo("<script>alert(1)</script>");
        assertThat(WebPage.fromHtml("https://example.com/a", "<p>&amp;lt;</p>").text()).isEqualTo("&lt;");
    }

    /**
     * The page is a stranger's, so an entity in it need not be a real one. A number no character has, or one
     * too long to be a number at all, is left as it was written rather than thrown.
     */
    @Test
    void anEntityThatNamesNoCharacterIsLeftAsItWasWritten() {
        assertThat(WebPage.fromHtml("https://example.com/a", "<p>a&#99999999999999;b</p>").text())
            .isEqualTo("a&#99999999999999;b");
        assertThat(WebPage.fromHtml("https://example.com/a", "<p>a&#1114112;b</p>").text())
            .isEqualTo("a&#1114112;b");
        assertThat(WebPage.fromHtml("https://example.com/a", "<p>a&#xFFFFFFFF;b</p>").text())
            .isEqualTo("a&#xFFFFFFFF;b");
    }

    @Test
    void itCollapsesEveryRunOfWhitespaceIntoOneSpace() {
        assertThat(WebPage.fromHtml("https://example.com/a", "<p>one\n\n   two\t\tthree</p>\n\n").text())
            .isEqualTo("one two three");
    }

    @Test
    void aPageWithNoTitleSimplyHasNone() {
        WebPage page = WebPage.fromHtml("https://example.com/a", "<html><body>just words</body></html>");

        assertThat(page.title()).isNull();
        assertThat(page.text()).isEqualTo("just words");
    }

    /** Plain text, JSON and the like arrive as they were written; turning their tags into spaces would ruin them. */
    @Test
    void plainTextArrivesAsItWasWritten() {
        WebPage page = WebPage.fromText("https://example.com/a.json", "{\"a\": 1, \"b\": \"<x>\"}");

        assertThat(page.text()).isEqualTo("{\"a\": 1, \"b\": \"<x>\"}");
        assertThat(page.title()).isNull();
        assertThat(page.cut()).isFalse();
    }

    // --- how much of a page is enough -------------------------------------------------------------------

    /** Enough for any page that answers a question; a longer one wants a narrower question, not a bigger cap. */
    @Test
    void aLongPageIsCutAndSaysSo() {
        WebPage page = WebPage.fromText("https://example.com/a", "x".repeat(WebPage.MAX_CHARS + 500));

        assertThat(page.text()).hasSize(WebPage.MAX_CHARS);
        assertThat(page.cut()).isTrue();
        assertThat(WebPage.MAX_CHARS).isEqualTo(16_000);
    }

    @Test
    void aPageThatFitsIsNotCut() {
        assertThat(WebPage.fromText("https://example.com/a", "x".repeat(WebPage.MAX_CHARS)).cut()).isFalse();
    }

    @Test
    void aLongHtmlPageIsCutOnItsTextRatherThanItsMarkup() {
        WebPage page = WebPage.fromHtml("https://example.com/a",
            "<html><body><script>" + "j".repeat(WebPage.MAX_CHARS * 2) + "</script><p>short</p></body></html>");

        assertThat(page.cut()).isFalse();
        assertThat(page.text()).isEqualTo("short");
    }

    // --- which content types are text at all ------------------------------------------------------------

    @Test
    void everyKindOfTextIsReadable() {
        assertThat(WebPage.of("https://example.com/a", "text/html; charset=utf-8", "<p>hi</p>").text())
            .isEqualTo("hi");
        assertThat(WebPage.of("https://example.com/a", "application/xhtml+xml", "<p>hi</p>").text())
            .isEqualTo("hi");
        assertThat(WebPage.of("https://example.com/a", "text/plain", "<p>hi</p>").text()).isEqualTo("<p>hi</p>");
        assertThat(WebPage.of("https://example.com/a", "text/markdown", "# hi").text()).isEqualTo("# hi");
        assertThat(WebPage.of("https://example.com/a", "application/json", "{\"a\":1}").text())
            .isEqualTo("{\"a\":1}");
        assertThat(WebPage.of("https://example.com/a", "application/xml", "<a>b</a>").text())
            .isEqualTo("<a>b</a>");
    }

    /** An image or an installer is not a page, and the model is told what it actually was. */
    @Test
    void anythingThatIsNotTextIsRefusedBySayingWhatItWas() {
        assertThatThrownBy(() -> WebPage.requireReadable("image/png"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("That address answered with image/png, which is not text Marvin can read.");
        assertThatThrownBy(() -> WebPage.requireReadable("application/pdf"))
            .hasMessageContaining("application/pdf");
        assertThatThrownBy(() -> WebPage.requireReadable("application/octet-stream"))
            .hasMessageContaining("application/octet-stream");
        assertThatThrownBy(() -> WebPage.requireReadable("video/mp4")).hasMessageContaining("video/mp4");
    }

    @Test
    void aPageThatSaysNothingAboutWhatItIsIsRefusedTooRatherThanGuessedAt() {
        assertThatThrownBy(() -> WebPage.requireReadable(null))
            .hasMessage("That address did not say what it answered with, so Marvin did not read it.");
        assertThatThrownBy(() -> WebPage.requireReadable("  ")).hasMessageContaining("did not say what");
    }

    // --- what the model reads ---------------------------------------------------------------------------

    @Test
    void theToolResultNamesThePageAndItsAddressBeforeItsText() {
        String result = WebPage.fromHtml("https://example.com/a",
            "<html><head><title>Quick start</title></head><body>the words</body></html>").toolResult();

        assertThat(result).isEqualTo("""
            Title: Quick start
            Address: https://example.com/a

            Quick start the words""");
    }

    @Test
    void aPageWithNoTitleNamesOnlyItsAddress() {
        assertThat(WebPage.fromText("https://example.com/a.txt", "the words").toolResult()).isEqualTo("""
            Address: https://example.com/a.txt

            the words""");
    }

    /** Said, never silent: a half-answer the model thinks is whole is worse than no answer. */
    @Test
    void aCutPageSaysSoAfterTheTextItDidGet() {
        String result = WebPage.fromText("https://example.com/a", "x".repeat(WebPage.MAX_CHARS + 1)).toolResult();

        assertThat(result).endsWith("\n\n(This page was cut after " + WebPage.MAX_CHARS
            + " characters; the rest was not read.)");
    }

    // --- the markup stripping, which a title and a body both go through -------------------------------

    @Test
    void plainText_dropsTheMarkupAndLeavesTheWords() {
        // A tag becomes a space, so a search engine's highlighting around a whole word comes out clean.
        assertThat(WebPage.plainText("A peer&#39;s <b>AllowedIPs</b>   is a\nrouting table"))
            .isEqualTo("A peer's AllowedIPs is a routing table");
        assertThat(WebPage.plainText(null)).isEmpty();
    }
}
