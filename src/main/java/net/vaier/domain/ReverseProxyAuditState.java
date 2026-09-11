package net.vaier.domain;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What Vaier has already told admins about its own reverse proxy config: the set of broken entries the last alert
 * named. A state exists only while something is broken — a clear config removes it, which is what lets a
 * config that breaks, is fixed and breaks again be alerted about a second time.
 *
 * <p><b>Persisted</b>, for exactly the reason {@link DiskPressureState} is. A latch in a field is wiped by
 * every redeploy — several a day here — which would turn "only on a transition" into "on every deploy", and
 * a heartbeat is the one thing this notification must never become.
 *
 * @param notifiedSignature {@link ReverseProxyFinding#signature()} for every finding the last alert carried
 */
public record ReverseProxyAuditState(Set<String> notifiedSignature) {

    public ReverseProxyAuditState {
        if (notifiedSignature == null || notifiedSignature.isEmpty()) {
            throw new IllegalArgumentException("A reverse proxy audit state needs the findings it was notified of");
        }
        notifiedSignature = new LinkedHashSet<>(notifiedSignature);
    }

    /** Whether {@code signature} is exactly what admins were last told — the same trouble, so not news. */
    public boolean isStillWhatAdminsWereTold(Set<String> signature) {
        return notifiedSignature.equals(signature);
    }
}
