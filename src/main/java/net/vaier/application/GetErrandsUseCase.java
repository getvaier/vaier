package net.vaier.application;

import net.vaier.domain.Errand;
import net.vaier.domain.Operator;

import java.util.List;

/**
 * This operator's <b>errands</b>, for the dialog that lists them (#360).
 *
 * <p>Named {@code getErrands} rather than {@code get}: {@code ChatService} already answers
 * {@code get(Operator)} with the operator's <b>Conversation</b>, and two methods with one erasure and two
 * return types cannot both be implemented.
 */
public interface GetErrandsUseCase {

    List<Errand> getErrands(Operator operator);
}
