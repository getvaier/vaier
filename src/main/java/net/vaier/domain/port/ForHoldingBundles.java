package net.vaier.domain.port;

import net.vaier.domain.Bundle;
import net.vaier.domain.Operator;

import java.util.List;
import java.util.Optional;

/**
 * Driven port holding the <b>Bundle</b>s Chat has offered (#360), each for the operator it was offered to.
 * Ephemeral: an hour, and never past the process.
 */
public interface ForHoldingBundles {

    void hold(Operator operator, Bundle bundle);

    Optional<Bundle> find(String id);

    /** Every bundle still held for this operator; whether one is live is the bundle's to say. */
    List<Bundle> heldFor(Operator operator);
}
