package net.vaier.adapter.driven;

import net.vaier.domain.ActionProposal;
import net.vaier.domain.ChatAction;
import net.vaier.domain.Operator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Proposals are ephemeral, like a session: held in memory, taken once, and gone with the process. */
class ActionProposalMemoryAdapterTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Operator GEIR = Operator.of("geir@example.com");

    private final ActionProposalMemoryAdapter adapter = new ActionProposalMemoryAdapter();

    /** Held for the operator it was proposed to, and taken exactly once, which also ends it being theirs. */
    @Test
    void aHeldProposalIsTheOperatorsUntilItIsTaken_exactlyOnce() {
        ActionProposal proposal = ActionProposal.propose(ChatAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"), NOW);
        adapter.hold(GEIR, proposal);

        assertThat(adapter.heldFor(GEIR)).containsExactly(proposal);
        assertThat(adapter.heldFor(Operator.of("ann@example.com"))).isEmpty();
        assertThat(adapter.take(proposal.id())).contains(proposal);
        assertThat(adapter.take(proposal.id())).isEmpty();
        assertThat(adapter.heldFor(GEIR)).isEmpty();
    }

    @Test
    void anUnknownIdIsNothing() {
        assertThat(adapter.take("no-such")).isEmpty();
    }

    /** A card nobody clicked must not pile up: holding a new one sweeps out the expired ones. */
    @Test
    void holdingANewProposalSweepsOutTheExpiredOnes() {
        ActionProposal old = ActionProposal.propose(ChatAction.LIFT_BLOCK, Map.of("address", "203.0.113.9"),
            NOW - ActionProposal.TTL.toMillis() - 1);
        adapter.hold(GEIR, old);
        adapter.hold(GEIR, ActionProposal.propose(ChatAction.LIFT_BLOCK, Map.of("address", "203.0.113.10"), NOW),
            NOW);

        assertThat(adapter.take(old.id())).isEmpty();
    }
}
