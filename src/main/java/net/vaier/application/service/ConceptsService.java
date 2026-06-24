package net.vaier.application.service;

import net.vaier.application.GetConceptsUseCase;
import net.vaier.domain.ConceptGroup;
import net.vaier.domain.OperatorGlossary;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConceptsService implements GetConceptsUseCase {

    @Override
    public List<ConceptGroup> getConcepts() {
        // The curated, grouped operator glossary is a domain decision — it lives in the domain.
        return OperatorGlossary.groups();
    }
}
