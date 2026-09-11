package net.vaier.application;

import net.vaier.domain.Operator;

/** Drop one <b>errand</b> of this operator's (#360). Another operator's id is an id Vaier does not have. */
public interface CancelErrandUseCase {

    void cancel(Operator operator, String id);
}
