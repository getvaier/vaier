package net.vaier.domain;

/** A value made safe inside a hand-rolled, double-quoted JSON string, as the SSE payloads are. */
final class JsonText {

    private JsonText() {}

    /**
     * Escape a value for embedding in a double-quoted JSON string.
     *
     * <p>Control characters are escaped, not only the backslash and double quote it used to handle. This
     * payload now carries a <b>host's own words</b>, and it is hand-rolled JSON delivered over SSE: the
     * emitter splits a payload on newlines into separate {@code data:} lines and the browser parses the
     * result inside a try/catch, so one raw newline costs the operator the whole message rather than one
     * line of it. The diagnostic is already reduced to a single printable line — this is the second lock
     * on the same door, and it covers the container name too, which Vaier does not choose either.
     */
    static String escaped(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
