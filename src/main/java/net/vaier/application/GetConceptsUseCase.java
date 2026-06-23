package net.vaier.application;

import net.vaier.domain.ConceptGroup;

import java.util.List;

public interface GetConceptsUseCase {

    /**
     * Return the operator-facing glossary as grouped, ordered {@link ConceptGroup}s for the in-app
     * <b>Concepts page</b>.
     */
    List<ConceptGroup> getConcepts();
}
