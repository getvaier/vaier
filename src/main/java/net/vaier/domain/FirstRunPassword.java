package net.vaier.domain;

/**
 * The one account Dex opens while the first-run door is open — no identity provider configured yet, or
 * one added from Settings that no admin has signed in through — and the password that opens it.
 * dex-init mints it; Vaier only reads it back and says it, once per boot, in its own log.
 */
public record FirstRunPassword(String email, String secret) {

    /** The block the operator reads in {@code docker compose logs vaier}: where, who, what. */
    public String banner(String domain) {
        String rule = "=".repeat(72);
        return String.join("\n",
            "",
            rule,
            "  No admin has signed in with Google or GitHub yet, so the first-run door is open.",
            "",
            "  Open      https://vaier." + domain,
            "  Sign in   with the first-run password",
            "  Email     " + email,
            "  Password  " + secret,
            "",
            "  The first sign-in becomes the admin. Add Google or GitHub under Settings,",
            "  Sign-in, to invite anyone else; this door closes once an admin signs in with it.",
            rule);
    }
}
