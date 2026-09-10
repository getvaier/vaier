package net.vaier.adapter.driven;

import net.vaier.domain.ActionProposal;
import net.vaier.domain.port.ForHoldingActionProposals;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Proposals held in memory for their ten minutes; a new one sweeps out the ones nobody clicked. */
@Component
public class ActionProposalMemoryAdapter implements ForHoldingActionProposals {

    private final Map<String, ActionProposal> held = new ConcurrentHashMap<>();

    @Override
    public void hold(ActionProposal proposal) {
        hold(proposal, System.currentTimeMillis());
    }

    void hold(ActionProposal proposal, long nowEpochMs) {
        held.values().removeIf(other -> other.expired(nowEpochMs));
        held.put(proposal.id(), proposal);
    }

    @Override
    public Optional<ActionProposal> take(String id) {
        return Optional.ofNullable(held.remove(id));
    }
}
