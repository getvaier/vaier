package net.vaier.adapter.driven;

import net.vaier.domain.ActionProposal;
import net.vaier.domain.AskAction;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Proposals are ephemeral, like a session: held in memory, taken once, and gone with the process. */
class ActionProposalMemoryAdapterTest {

    private static final long NOW = 1_700_000_000_000L;

    private final ActionProposalMemoryAdapter adapter = new ActionProposalMemoryAdapter();

    @Test
    void aHeldProposalIsTakenExactlyOnce() {
        ActionProposal proposal = ActionProposal.propose(AskAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"), NOW);
        adapter.hold(proposal);

        assertThat(adapter.take(proposal.id())).contains(proposal);
        assertThat(adapter.take(proposal.id())).isEmpty();
    }

    @Test
    void anUnknownIdIsNothing() {
        assertThat(adapter.take("no-such")).isEmpty();
    }

    /** A card nobody clicked must not pile up: holding a new one sweeps out the expired ones. */
    @Test
    void holdingANewProposalSweepsOutTheExpiredOnes() {
        ActionProposal old = ActionProposal.propose(AskAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"),
            NOW - ActionProposal.TTL.toMillis() - 1);
        adapter.hold(old);
        adapter.hold(ActionProposal.propose(AskAction.LIFT_BLOCK, Map.of("address", "203.0.113.10"), NOW),
            NOW);

        assertThat(adapter.take(old.id())).isEmpty();
    }
}
