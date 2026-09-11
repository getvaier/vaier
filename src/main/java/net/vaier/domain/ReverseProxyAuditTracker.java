package net.vaier.domain;

import net.vaier.domain.port.ForPersistingReverseProxyAuditState;

import java.util.Optional;
import java.util.Set;

/**
 * Decides whether admins should hear about a {@link ReverseProxyAudit} right now. It owns the whole rule and
 * reaches its own state through {@link ForPersistingReverseProxyAuditState}; the caller hands the port in
 * and then only decides <em>whom to tell</em>.
 *
 * <p><b>On a transition, never on a timer.</b> The audit runs on every five-minute sweep, so a config broken
 * for a week would otherwise mail about the same four entries two thousand times. What is news is the
 * <em>set</em> of broken entries changing: a newly orphaned middleware is news, the same one still sitting
 * there is not, and — the case that would otherwise go unsaid — one of several being fixed is news too,
 * because what admins were last told is no longer what is true.
 *
 * <p><b>And the first observation speaks.</b> The state outlives the process precisely so that "first ever"
 * means first ever rather than first since the last deploy; without that, the silent-baseline treatment that
 * kept a 89%-full disk quiet for months would repeat here exactly.
 */
public class ReverseProxyAuditTracker {

    /** What Vaier should do about the reverse proxy config this sweep. */
    public enum Outcome {
        /** Nothing to say: clear and never in trouble, or in exactly the trouble admins already know about. */
        QUIET,
        /** Broken, and not in the way admins were last told — send the findings. */
        ALERT,
        /** Clear after having been broken — send the one all-clear. */
        RECOVERED
    }

    /** What the tracker decided, and the audit it decided about. */
    public record Verdict(Outcome outcome, ReverseProxyAudit audit) { }

    private final ForPersistingReverseProxyAuditState states;

    public ReverseProxyAuditTracker(ForPersistingReverseProxyAuditState states) {
        this.states = states;
    }

    /** Record this sweep's {@code audit} and decide what admins should hear. */
    public synchronized Verdict observe(ReverseProxyAudit audit) {
        Optional<ReverseProxyAuditState> stored = states.find();

        if (audit.isClean()) {
            if (stored.isEmpty()) {
                return new Verdict(Outcome.QUIET, audit);
            }
            states.clear();
            return new Verdict(Outcome.RECOVERED, audit);
        }

        Set<String> signature = audit.signature();
        if (stored.isPresent() && stored.get().isStillWhatAdminsWereTold(signature)) {
            return new Verdict(Outcome.QUIET, audit);
        }
        states.save(new ReverseProxyAuditState(signature));
        return new Verdict(Outcome.ALERT, audit);
    }
}
