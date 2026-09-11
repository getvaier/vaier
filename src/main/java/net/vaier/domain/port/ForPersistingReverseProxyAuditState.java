package net.vaier.domain.port;

import net.vaier.domain.ReverseProxyAuditState;

import java.util.Optional;

/**
 * Driven port for persisting {@link ReverseProxyAuditState} — which routing-config findings admins have
 * already been emailed about.
 *
 * <p>On disk rather than in a field, for the reason {@link ForPersistingDiskPressureState} is: a latch the
 * process owns is wiped by every redeploy, and a transition-only notification whose "previous" is always
 * empty mails on every deploy instead. An absent state is the healthy first-boot state, never an error.
 */
public interface ForPersistingReverseProxyAuditState {

    /** What admins were last told, if anything. Empty means nothing is outstanding. */
    Optional<ReverseProxyAuditState> find();

    /** Persist {@code state}, replacing whatever was there. */
    void save(ReverseProxyAuditState state);

    /** Forget it — the config is clear and there is nothing outstanding. */
    void clear();
}
