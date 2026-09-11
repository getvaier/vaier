package net.vaier.domain;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One page Marvin read off the public internet (#360): its address, its own name, its words, and whether
 * there were more words than were worth reading.
 *
 * <p>A page is read to answer a question, so the decisions are all about what is left once the markup is
 * gone. A script block is a page's machinery and usually most of its weight; a stylesheet, a {@code noscript}
 * and an inline drawing are no better. What survives is the title first — a page's own name is the fastest
 * way to tell whether it is the right page — and then its words, with every run of whitespace collapsed.
 *
 * <p>Which content types are text at all is here too, as a list of what <em>is</em> readable: an image, a PDF
 * or an installer is not a page, and a content type nobody declared is not guessed at. And a long page is cut
 * and <em>says</em> it was cut, exactly as {@link CommandOutcome} does — a half-answer the model believes is
 * whole is worse than no answer.
 */
public record WebPage(String url, String title, String text, boolean cut) {

    /** Enough for any page that answers a question; a longer one wants a narrower question, not a bigger cap. */
    public static final int MAX_CHARS = 16_000;

    /** The content types that are words. Everything else is refused by name. */
    private static final Set<String> READABLE = Set.of("application/json", "application/xml",
        "application/xhtml+xml", "application/rss+xml", "application/atom+xml", "application/javascript",
        "application/ld+json");
    private static final Set<String> MARKUP = Set.of("text/html", "application/xhtml+xml");

    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern MACHINERY =
        Pattern.compile("(?is)<(script|style|noscript|svg)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * A page as its content type says it is: markup has its text taken out, everything else arrives as it
     * was written. Refuses a content type that is not text at all.
     */
    public static WebPage of(String url, String contentType, String body) {
        requireReadable(contentType);
        return isMarkup(contentType) ? fromHtml(url, body) : fromText(url, body);
    }

    public static WebPage fromHtml(String url, String html) {
        String source = html == null ? "" : html;
        String title = title(source);
        // The title is read out and put first, so the element itself must not say it a second time.
        String words = plainText(TITLE.matcher(source).replaceAll(" "));
        String text = title == null ? words : (words.isEmpty() ? title : title + " " + words);
        return cap(url, title, text);
    }

    public static WebPage fromText(String url, String body) {
        return cap(url, null, body == null ? "" : body);
    }

    /**
     * Refuses a content type that is not words, by name — so the model learns that the address it found was
     * an image, and looks for the page that links to it instead.
     */
    public static void requireReadable(String contentType) {
        String type = type(contentType);
        if (type.isEmpty()) {
            throw new IllegalArgumentException(
                "That address did not say what it answered with, so Marvin did not read it.");
        }
        if (!type.startsWith("text/") && !READABLE.contains(type)) {
            throw new IllegalArgumentException("That address answered with " + type
                + ", which is not text Marvin can read.");
        }
    }

    /** What the model reads: which page this is, where it is, and then the page. */
    public String toolResult() {
        StringBuilder result = new StringBuilder();
        if (title != null && !title.isBlank()) {
            result.append("Title: ").append(title).append('\n');
        }
        result.append("Address: ").append(url).append("\n\n").append(text);
        if (cut) {
            result.append("\n\n(This page was cut after ").append(MAX_CHARS)
                .append(" characters; the rest was not read.)");
        }
        return result.toString();
    }

    /**
     * Markup turned into words: the machinery dropped, every tag a space, the entities decoded, and the
     * whitespace collapsed. Its own method rather than part of {@link #fromHtml} because a title is stripped
     * the same way a body is, and because it is worth a test of its own.
     */
    static String plainText(String html) {
        if (html == null) {
            return "";
        }
        String words = MACHINERY.matcher(html).replaceAll(" ");
        words = COMMENT.matcher(words).replaceAll(" ");
        words = TAG.matcher(words).replaceAll(" ");
        // The entities come after the tags, so a decoded &lt; can never be read as the start of one.
        words = decode(words);
        return WHITESPACE.matcher(words).replaceAll(" ").trim();
    }

    // --- the reading ------------------------------------------------------------------------------------

    private static WebPage cap(String url, String title, String text) {
        boolean cut = text.length() > MAX_CHARS;
        return new WebPage(url, title, cut ? text.substring(0, MAX_CHARS) : text, cut);
    }

    private static String title(String html) {
        Matcher found = TITLE.matcher(html);
        if (!found.find()) {
            return null;
        }
        String title = plainText(found.group(1));
        return title.isEmpty() ? null : title;
    }

    private static boolean isMarkup(String contentType) {
        return MARKUP.contains(type(contentType));
    }

    /** The type without its parameters: {@code text/html; charset=utf-8} is {@code text/html}. */
    private static String type(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon))
            .trim().toLowerCase(Locale.ROOT);
    }

    private static String decode(String words) {
        String decoded = NUMERIC_ENTITY.matcher(words).replaceAll(match ->
            Matcher.quoteReplacement(character(match.group(1), match.group())));
        decoded = decoded.replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'");
        // &amp; last, so &amp;lt; decodes to &lt; and not to <.
        return decoded.replace("&amp;", "&");
    }

    /**
     * One numeric entity's character. The page is a stranger's, so the number need not name a character — or
     * even fit in one; an entity that names nothing is left exactly as it was written.
     */
    private static String character(String digits, String asWritten) {
        try {
            boolean hex = digits.startsWith("x") || digits.startsWith("X");
            int code = Integer.parseInt(hex ? digits.substring(1) : digits, hex ? 16 : 10);
            return Character.isValidCodePoint(code) ? Character.toString(code) : asWritten;
        } catch (NumberFormatException e) {
            return asWritten;
        }
    }
}
