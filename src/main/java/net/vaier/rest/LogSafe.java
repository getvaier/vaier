package net.vaier.rest;

/**
 * Web-layer helper that neutralises log forging (CRLF injection) when user-supplied request fields
 * — subdomains, peer names, machine names — are written to log lines. Any run of CR/LF characters
 * is collapsed to a single {@code _} so a value can never break out of its line and forge a new
 * log entry. This is a logging concern of the REST layer only; it performs no business decision.
 */
final class LogSafe {

    private LogSafe() {
    }

    /**
     * Returns {@code value} with every run of carriage-return/line-feed characters replaced by a
     * single underscore, so it is safe to embed in a single log line. Null is passed through.
     */
    static String forLog(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("[\r\n]+", "_");
    }
}
