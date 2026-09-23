package net.vaier.domain;

/**
 * The one account Dex opens on a first-run stack — no identity provider configured yet — and the
 * password that opens it. dex-init mints it; Vaier only reads it back and says it, once per boot, in
 * its own log. It exists exactly as long as no provider does.
 */
public record FirstRunPassword(String email, String secret) {

    /** The block the operator reads in {@code docker compose logs vaier}: where, who, what. */
    public String banner(String domain) {
        String rule = "=".repeat(72);
        return String.join("\n",
            "",
            rule,
            "  No sign-in provider is configured, so Vaier opened a first-run door.",
            "",
            "  Open      https://vaier." + domain,
            "  Sign in   with the first-run password",
            "  Email     " + email,
            "  Password  " + secret,
            "",
            "  The first sign-in becomes the admin. Register Google or GitHub when you",
            "  want to invite anyone else; this door closes the moment a provider exists.",
            rule);
    }
}
