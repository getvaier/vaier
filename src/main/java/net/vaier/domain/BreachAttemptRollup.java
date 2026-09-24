package net.vaier.domain;

import java.util.List;

/**
 * The breach attempts among the block decisions {@link BreachAttemptTracker} found newly appeared in one
 * sweep, and how that reads as an email to admins.
 *
 * <p><b>Not every new block decision is one.</b> Only a {@link ThreatKind#CREDENTIAL_ATTACK} on a source
 * outside the operator's own networks qualifies — see {@link #from(List, TrustedNetworks)}. Blind scanning
 * is silent, and a ban on the operator's own network is a {@link LockoutWarning} instead.
 *
 * <p><b>One sweep, one mail.</b> A burst of decisions from one attacker is one message listing
 * all of them, not one message per decision — the sibling of {@link ImageUpdateRollup}'s own
 * rollup reasoning. It renders itself; {@code NotificationService} only sequences the send.
 *
 * <p>The mail says what CrowdSec did and stops. Lifting the block or trusting the address are the Security
 * view's verbs, and the body links there rather than growing affordances of its own.
 *
 * @param decisions the newly-appeared block decisions that qualify, in the order they should be listed
 */
public record BreachAttemptRollup(List<BlockDecision> decisions) {

    /**
     * The breach attempts among one sweep's newly-appeared decisions — which is far fewer than all of
     * them, and on most sweeps is none.
     *
     * <p>Two things are dropped here, for different reasons. {@link ThreatKind#BLIND_SCANNING} is dropped
     * because a banned scanner is CrowdSec working correctly and nobody can act on it; it stays fully
     * visible in the Security view and on the Map, which is what made this silence affordable. A decision
     * that {@link BlockDecision#locksOut(TrustedNetworks) locks the operator out} is dropped because it is
     * not an attack at all — it raises its own {@link LockoutWarning}, and describing the operator's own
     * network as an attempt to break in would be a straightforwardly false thing to send them.
     *
     * @param newlyAppeared  the decisions this sweep saw for the first time
     * @param trustedNetworks the operator's own networks; may be null, in which case nothing is treated as
     *                        a lockout
     */
    public static BreachAttemptRollup from(List<BlockDecision> newlyAppeared,
                                           TrustedNetworks trustedNetworks) {
        if (newlyAppeared == null) {
            return new BreachAttemptRollup(List.of());
        }
        return new BreachAttemptRollup(newlyAppeared.stream()
            .filter(decision -> decision.threatKind().worthEmailing())
            .filter(decision -> !decision.locksOut(trustedNetworks))
            // #349: a decision the operator placed themselves is never a breach attempt, whatever its
            // reason marker happens to read as to ThreatKind — nobody is attacking on this one.
            .filter(decision -> !decision.handBlocked())
            .toList());
    }

    /** Whether there is anything to say. A sweep that found nothing new sends no mail at all. */
    public boolean worthSending() {
        return decisions != null && !decisions.isEmpty();
    }

    /** Subject line — the one decision, or a count when there are several. */
    public String subject() {
        if (decisions.size() == 1) {
            return "[Vaier] Breach attempt: " + decisions.get(0).label();
        }
        return "[Vaier] Breach attempt: " + decisions.size() + " new block decisions";
    }

    /**
     * Body: every newly-appeared decision, then the one thing the operator needs to know — it's
     * already refused at the edge. {@code baseDomain} builds the Vaier UI link, omitted when null
     * or blank.
     */
    public String body(String baseDomain) {
        StringBuilder body = new StringBuilder();
        body.append(decisions.size() == 1
            ? "CrowdSec blocked a credential attack — somebody working on getting in, not a passing scanner:\n\n"
            : "CrowdSec blocked credential attacks — somebody working on getting in, not passing scanners:\n\n");
        for (BlockDecision decision : decisions) {
            body.append("  • ").append(decision.label()).append("\n");
        }
        body.append("\nEach source above is refused at the edge before it reaches oauth2-proxy or a published service.\n");
        body.append("\nRoutine blind scanning is blocked too and is deliberately not mailed — the Security view lists\n")
            .append("every block decision, and the Map plots the ones CrowdSec can place.\n");
        if (baseDomain != null && !baseDomain.isBlank()) {
            body.append("\nVaier UI: https://")
                .append(new VaierHostnames(baseDomain).vaierServerFqdn())
                .append("/\n");
        }
        return body.toString();
    }
}
