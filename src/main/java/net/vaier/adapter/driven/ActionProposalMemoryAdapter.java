package net.vaier.adapter.driven;

import net.vaier.domain.ActionProposal;
import net.vaier.domain.Operator;
import net.vaier.domain.port.ForHoldingActionProposals;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Proposals held in memory for their ten minutes; a new one sweeps out the ones nobody clicked. */
@Component
public class ActionProposalMemoryAdapter implements ForHoldingActionProposals {

    private record Held(Operator operator, ActionProposal proposal) {}

    private final Map<String, Held> held = new ConcurrentHashMap<>();

    @Override
    public void hold(Operator operator, ActionProposal proposal) {
        hold(operator, proposal, System.currentTimeMillis());
    }

    void hold(Operator operator, ActionProposal proposal, long nowEpochMs) {
        held.values().removeIf(other -> other.proposal().expired(nowEpochMs));
        held.put(proposal.id(), new Held(operator, proposal));
    }

    @Override
    public Optional<ActionProposal> take(String id) {
        return Optional.ofNullable(held.remove(id)).map(Held::proposal);
    }

    @Override
    public List<ActionProposal> heldFor(Operator operator) {
        return held.values().stream().filter(entry -> entry.operator().equals(operator)).map(Held::proposal).toList();
    }
}
