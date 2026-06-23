package net.vaier.domain;

import java.util.List;

/**
 * A titled grouping of related {@link Concept}s, as the operator glossary presents them
 * (e.g. "Machines", "Services", "DNS &amp; access"). The grouping is operator-facing copy and is
 * curated in {@link OperatorGlossary}.
 */
public record ConceptGroup(String title, List<Concept> concepts) {}
