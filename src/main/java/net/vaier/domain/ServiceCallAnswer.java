package net.vaier.domain;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * What a published service answered a <b>Service call</b>: its status, what it said it was, and at most the
 * first part of its body. No header is kept, so nothing a service sets — a cookie, a token — can reach the
 * model.
 */
public record ServiceCallAnswer(int status, String contentType, byte[] body, boolean more) {

    /** As much as a web page gets: enough for any answer, and a longer one wants a narrower path. */
    public static final int MAX_CHARS = WebPage.MAX_CHARS;
    /** How much of a failed answer the operator is shown. */
    public static final int SNIPPET_CHARS = 300;

    public boolean succeeded() {
        return status >= 200 && status < 300;
    }

    /**
     * How much of a success the kept conversation holds. Far less than the model's own read, since the thread is
     * re-sent with every later question; a longer answer wants a narrower path.
     */
    public static final int KEPT_CHARS = 2_000;

    /** What the model reads: text and JSON through, binary measured, a long body cut and saying so. */
    public String forModel(String service) {
        return told(service, MAX_CHARS);
    }

    /** What came back, as the thread keeps it after a yes; nothing for a failure, whose start is in the details. */
    public String cameBack(String service) {
        return succeeded() ? told(service, KEPT_CHARS) : null;
    }

    private String told(String service, int maxChars) {
        String type = type();
        String head = service + " answered " + status + (type.isEmpty() ? "" : " (" + type + ")");
        if (body.length == 0 && !more) {
            return head + ", with no body.";
        }
        if (!isText()) {
            return head + ": binary, " + (more ? "more than " : "") + body.length + " bytes.";
        }
        String text = text();
        if (text.length() > maxChars) {
            return head + ".\n\n" + text.substring(0, maxChars) + "\n\n(The body was cut after " + maxChars
                + " characters; the rest was not read.)";
        }
        return head + ".\n\n" + text + (more ? "\n\n(The body was cut; the rest was not read.)" : "");
    }

    /** The outcome of a yes, in plain words; the status, and the start of what the service said, are the details. */
    public ActionWording outcome(String service) {
        String snippet = isText() ? snippet() : "";
        String details = "It answered " + status + (snippet.isEmpty() ? "." : ": " + snippet);
        return succeeded()
            ? new ActionWording("Done — " + service + " accepted it.", details)
            : new ActionWording("That did not work — " + service + " did not accept it.", details);
    }

    private String snippet() {
        String text = type().equals("text/html") ? WebPage.plainText(text()) : text().replaceAll("\\s+", " ").trim();
        return text.length() <= SNIPPET_CHARS ? text : text.substring(0, SNIPPET_CHARS) + "…";
    }

    /** A declared type is taken at its word; an undeclared one is words unless it carries a NUL. */
    private boolean isText() {
        String type = type();
        if (type.isEmpty()) {
            for (byte b : body) {
                if (b == 0) {
                    return false;
                }
            }
            return true;
        }
        return WebPage.isReadable(type) || type.endsWith("+json") || type.endsWith("+xml");
    }

    private String type() {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    private String text() {
        return new String(body, charset());
    }

    private Charset charset() {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String piece = part.trim();
                if (piece.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    try {
                        return Charset.forName(piece.substring("charset=".length()).replace("\"", "").trim());
                    } catch (RuntimeException e) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
