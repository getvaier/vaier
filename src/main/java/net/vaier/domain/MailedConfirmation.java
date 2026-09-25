package net.vaier.domain;

import lombok.Builder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * A <b>Mailed confirmation</b>: a <b>Chat action</b> an <b>errand</b> proposed while nobody was watching,
 * mailed to the operator it runs for as one <b>approval link</b>. It lives a day and is taken once. Only the
 * digest of the link's token is kept, so the file it lives in opens nothing.
 */
@Builder
public record MailedConfirmation(String tokenDigest, Operator operator, ActionProposal proposal,
                                 long mailedAtEpochMs) {

    public static final Duration TTL = Duration.ofHours(24);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A new confirmation for {@code operator}, and the one token that opens it. */
    public static Minted mint(ActionProposal proposal, Operator operator, long nowEpochMs) {
        if (operator.email().isEmpty()) {
            throw new IllegalArgumentException(
                "Nobody is signed in with an address to ask, so this cannot be proposed by mail.");
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new Minted(token, MailedConfirmation.builder()
            .tokenDigest(digest(token))
            .operator(operator)
            .proposal(proposal)
            .mailedAtEpochMs(nowEpochMs)
            .build());
    }

    public boolean expired(long nowEpochMs) {
        return nowEpochMs - mailedAtEpochMs >= TTL.toMillis();
    }

    /** Compared in constant time, so how long a wrong token takes says nothing about the right one. */
    public boolean opensWith(String token) {
        return token != null && MessageDigest.isEqual(
            digest(token).getBytes(StandardCharsets.US_ASCII), tokenDigest.getBytes(StandardCharsets.US_ASCII));
    }

    /** What Marvin is told. The one lie this must prevent is "done". */
    public String toolResult() {
        return "Mailed to the operator for a yes: \"" + proposal.sentence() + "\" Nothing has happened yet, and "
            + "nothing will unless they say yes from the mail. Say in your report that it is waiting for their "
            + "yes, and do not say it is done.";
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this Java", e);
        }
    }

    /** The confirmation as it is kept, beside the token that goes in the mail and nowhere else. */
    public record Minted(String token, MailedConfirmation confirmation) {

        public String recipient() {
            return confirmation.operator().email().orElseThrow();
        }

        public String subject() {
            return "Marvin asks: " + confirmation.proposal().sentence();
        }

        public String body(String domain) {
            String link = "https://vaier." + (domain == null ? "" : domain.trim()) + "/chat/approvals/" + token;
            return "While you were away, Marvin would like to do this:\n\n"
                + "  " + confirmation.proposal().sentence() + "\n\n"
                + "Say yes or no here: " + link + "\n\n"
                + "Nothing happens unless you say yes. The link works once, for a day, while you are signed in "
                + "to Vaier.\n\n"
                + "Marvin\n(who could have just done it, but was not allowed to)\n";
        }
    }
}
