package net.vaier.rest;

import net.vaier.application.GetConceptsUseCase;
import net.vaier.domain.ConceptGroup;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves the operator-facing glossary that backs the in-app <b>Concepts page</b>. Loaded inside the
 * already-authenticated admin shell, so no special auth handling is needed here.
 */
@RestController
public class ConceptsController {

    private final GetConceptsUseCase getConceptsUseCase;

    public ConceptsController(GetConceptsUseCase getConceptsUseCase) {
        this.getConceptsUseCase = getConceptsUseCase;
    }

    @GetMapping("/concepts")
    public List<ConceptGroupDto> concepts() {
        return getConceptsUseCase.getConcepts().stream()
            .map(ConceptsController::toDto)
            .toList();
    }

    private static ConceptGroupDto toDto(ConceptGroup group) {
        List<ConceptDto> concepts = group.concepts().stream()
            .map(c -> new ConceptDto(c.slug(), c.term(), c.definition(), c.whyYouCare()))
            .toList();
        return new ConceptGroupDto(group.title(), concepts);
    }

    record ConceptGroupDto(String title, List<ConceptDto> concepts) {}

    record ConceptDto(String slug, String term, String definition, String whyYouCare) {}
}
