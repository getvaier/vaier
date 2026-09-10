package net.vaier.domain;

/**
 * The mail that carries a <b>Bundle</b>'s link (#360): what the zip is called, what it holds, where to fetch
 * it, and how long that works. Sent when the operator would rather fetch a large bundle when convenient
 * than wait on the card.
 */
public record BundleMailNotice(String subject, String body) {

    public static BundleMailNotice of(Bundle bundle, String domain) {
        String link = "https://vaier." + (domain == null ? "" : domain.trim()) + "/chat/bundles/" + bundle.id();
        String subject = "Your files from " + bundle.machineLabel() + ": " + bundle.name();
        String body = "Marvin has your files ready: " + bundle.name() + " (" + bundle.describe() + ").\n\n"
            + "Download: " + link + "\n\n"
            + "The link works for a day, while you are signed in to Vaier. Nothing was copied anywhere; the "
            + "zip is built as it downloads.\n\n"
            + "Marvin\n(who would, for the record, rather you had wanted something smaller)\n";
        return new BundleMailNotice(subject, body);
    }
}
