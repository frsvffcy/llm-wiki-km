package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.KnowledgeCandidateType;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/** Explicit policy for resolving analysis candidates into the independent Wiki page taxonomy. */
@Component
public class CandidatePageTypeResolver {

    private static final Set<WikiPageType> CONCEPT_TYPES = EnumSet.of(
            WikiPageType.CONCEPT, WikiPageType.TECHNOLOGY, WikiPageType.PROJECT,
            WikiPageType.PERSON, WikiPageType.ORGANIZATION);
    private static final Set<WikiPageType> FACT_TYPES = EnumSet.of(
            WikiPageType.CONCEPT, WikiPageType.TECHNOLOGY, WikiPageType.PROJECT,
            WikiPageType.PERSON, WikiPageType.ORGANIZATION, WikiPageType.REFERENCE);
    private static final Set<WikiPageType> PROCEDURE_TYPES = EnumSet.of(
            WikiPageType.HOWTO, WikiPageType.TROUBLESHOOTING);
    private static final Set<WikiPageType> DECISION_TYPES = EnumSet.of(WikiPageType.DECISION);
    private static final Set<WikiPageType> REFERENCE_TYPES = EnumSet.of(
            WikiPageType.REFERENCE, WikiPageType.TECHNOLOGY);

    public WikiPageType resolve(KnowledgeCandidateType candidateType, WikiPageType requestedPageType) {
        if (candidateType == null) {
            throw unsupported("Candidate type must not be null");
        }
        if (requestedPageType == null) {
            return switch (candidateType) {
                case CONCEPT -> WikiPageType.CONCEPT;
                case DECISION -> WikiPageType.DECISION;
                case REFERENCE -> WikiPageType.REFERENCE;
                case FACT -> throw ambiguous("FACT requires an explicit pageType because a fact has no single Wiki home");
                case PROCEDURE -> throw ambiguous(
                        "PROCEDURE requires an explicit HOWTO or TROUBLESHOOTING pageType");
            };
        }

        Set<WikiPageType> allowed = switch (candidateType) {
            case CONCEPT -> CONCEPT_TYPES;
            case FACT -> FACT_TYPES;
            case PROCEDURE -> PROCEDURE_TYPES;
            case DECISION -> DECISION_TYPES;
            case REFERENCE -> REFERENCE_TYPES;
        };
        if (!allowed.contains(requestedPageType)) {
            throw unsupported("Candidate type " + candidateType + " cannot resolve to " + requestedPageType
                    + "; allowed page types: " + allowed);
        }
        return requestedPageType;
    }

    private static WikiDraftValidationException ambiguous(String message) {
        return new WikiDraftValidationException(
                WikiDraftValidationException.Reason.AMBIGUOUS_CANDIDATE_MAPPING, message);
    }

    private static WikiDraftValidationException unsupported(String message) {
        return new WikiDraftValidationException(
                WikiDraftValidationException.Reason.UNSUPPORTED_CANDIDATE_MAPPING, message);
    }
}
