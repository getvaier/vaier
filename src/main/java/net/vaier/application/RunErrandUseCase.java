package net.vaier.application;

import net.vaier.domain.Errand;
import net.vaier.domain.ToolOffer;

import java.util.List;

/**
 * Run one <b>errand</b> with nobody watching (#360): Marvin answers it alone from the reads he is offered,
 * the operator is mailed what he found unless there was nothing to say, and the errand moves on to its next
 * time.
 */
public interface RunErrandUseCase {

    void run(Errand errand, List<ToolOffer> tools);
}
