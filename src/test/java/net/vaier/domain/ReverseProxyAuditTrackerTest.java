package net.vaier.domain;

import net.vaier.domain.ReverseProxyConfig.ConfiguredMiddleware;
import net.vaier.domain.ReverseProxyConfig.Protocol;
import net.vaier.domain.port.ForPersistingReverseProxyAuditState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When admins hear about the reverse proxy audit, and — much more of the time — when they do not. Findings only,
 * on a transition only: the same broken entries on the next sweep are not news, a newly broken one is.
 */
class ReverseProxyAuditTrackerTest {

    /** The state as it really behaves — on disk in production, in a field here. */
    private static class InMemoryState implements ForPersistingReverseProxyAuditState {
        private ReverseProxyAuditState held;

        @Override public Optional<ReverseProxyAuditState> find() { return Optional.ofNullable(held); }
        @Override public void save(ReverseProxyAuditState state) { this.held = state; }
        @Override public void clear() { this.held = null; }
    }

    private final InMemoryState state = new InMemoryState();
    private final ReverseProxyAuditTracker tracker = new ReverseProxyAuditTracker(state);

    private static ReverseProxyAudit auditWithOrphans(String... middlewareNames) {
        List<ConfiguredMiddleware> middlewares = List.of(middlewareNames).stream()
            .map(name -> ConfiguredMiddleware.builder().protocol(Protocol.HTTP).name(name).build())
            .toList();
        return ReverseProxyAudit.of(ReverseProxyConfig.builder().middlewares(middlewares).build());
    }

    private static final ReverseProxyAudit CLEAN = ReverseProxyAudit.of(ReverseProxyConfig.empty());

    @Test
    void aCleanConfigNeverSeenInTroubleSaysNothing() {
        assertThat(tracker.observe(CLEAN).outcome())
            .isEqualTo(ReverseProxyAuditTracker.Outcome.QUIET);
    }

    @Test
    void theFirstSweepThatFindsSomethingAlerts() {
        ReverseProxyAuditTracker.Verdict verdict = tracker.observe(auditWithOrphans("a-orphan"));

        assertThat(verdict.outcome()).isEqualTo(ReverseProxyAuditTracker.Outcome.ALERT);
        assertThat(verdict.audit().findings()).hasSize(1);
    }

    @Test
    void theSameFindingsOnTheNextSweepAreNotNews() {
        tracker.observe(auditWithOrphans("a-orphan"));

        assertThat(tracker.observe(auditWithOrphans("a-orphan")).outcome())
            .isEqualTo(ReverseProxyAuditTracker.Outcome.QUIET);
    }

    @Test
    void aNewlyBrokenEntryAlertsAgain() {
        tracker.observe(auditWithOrphans("a-orphan"));

        assertThat(tracker.observe(auditWithOrphans("a-orphan", "b-orphan")).outcome())
            .isEqualTo(ReverseProxyAuditTracker.Outcome.ALERT);
    }

    @Test
    void oneOfSeveralBeingFixedAlertsToo() {
        // The set changed, so what admins were last told is no longer what is true.
        tracker.observe(auditWithOrphans("a-orphan", "b-orphan"));

        assertThat(tracker.observe(auditWithOrphans("a-orphan")).outcome())
            .isEqualTo(ReverseProxyAuditTracker.Outcome.ALERT);
    }

    @Test
    void everythingBeingFixedIsTheOneAllClear() {
        tracker.observe(auditWithOrphans("a-orphan"));

        assertThat(tracker.observe(CLEAN).outcome())
            .isEqualTo(ReverseProxyAuditTracker.Outcome.RECOVERED);
    }

    @Test
    void theAllClearIsSaidOnceAndNotOnEverySweepAfterIt() {
        tracker.observe(auditWithOrphans("a-orphan"));
        tracker.observe(CLEAN);

        assertThat(tracker.observe(CLEAN).outcome())
            .isEqualTo(ReverseProxyAuditTracker.Outcome.QUIET);
    }

    @Test
    void troubleAfterAnAllClearAlertsAgain() {
        tracker.observe(auditWithOrphans("a-orphan"));
        tracker.observe(CLEAN);

        assertThat(tracker.observe(auditWithOrphans("a-orphan")).outcome())
            .isEqualTo(ReverseProxyAuditTracker.Outcome.ALERT);
    }

    @Test
    void whatAdminsWereToldOutlivesTheProcess() {
        // The disk-pressure bug in miniature: a latch in a field is wiped by every redeploy, and several a
        // day here would have turned "on a transition" into "every deploy".
        tracker.observe(auditWithOrphans("a-orphan"));

        ReverseProxyAuditTracker afterRedeploy = new ReverseProxyAuditTracker(state);

        assertThat(afterRedeploy.observe(auditWithOrphans("a-orphan")).outcome())
            .isEqualTo(ReverseProxyAuditTracker.Outcome.QUIET);
    }

    @Test
    void theStateIsForgottenOnceTheConfigIsClear() {
        tracker.observe(auditWithOrphans("a-orphan"));
        tracker.observe(CLEAN);

        assertThat(state.find()).isEmpty();
    }
}
