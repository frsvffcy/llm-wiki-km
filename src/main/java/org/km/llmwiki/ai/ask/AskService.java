package org.km.llmwiki.ai.ask;

import org.km.llmwiki.ai.answer.AnswerClient;
import org.km.llmwiki.ai.answer.AnswerClientException;
import org.km.llmwiki.ai.answer.AnswerContext;
import org.km.llmwiki.ai.answer.AnswerContextAssembler;
import org.km.llmwiki.ai.answer.AnswerResult;
import org.km.llmwiki.ai.answer.AnswerFailureType;
import org.km.llmwiki.ai.answer.CitationValidationException;
import org.km.llmwiki.rag.EvidenceBundle;
import org.km.llmwiki.rag.RetrievalService;
import org.km.llmwiki.rag.RetrievalUnavailableException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Single application-level Ask orchestration boundary.
 *
 * <p>Retrieval owns candidate search and authority revalidation, context assembly owns the
 * second budget boundary, and AnswerClient owns grounded prompt/provider response validation.
 * This service coordinates those contracts without introducing controller, session, or agent
 * responsibilities.
 */
@Service
public class AskService {

    private final RetrievalService retrievalService;
    private final AnswerContextAssembler contextAssembler;
    private final AnswerClient answerClient;

    public AskService(RetrievalService retrievalService, AnswerContextAssembler contextAssembler,
                      AnswerClient answerClient) {
        this.retrievalService = retrievalService;
        this.contextAssembler = contextAssembler;
        this.answerClient = answerClient;
    }

    public AskResult ask(AskRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ask request must not be null");
        }

        EvidenceBundle evidence;
        try {
            evidence = retrievalService.retrieve(request.retrievalRequest());
        } catch (RetrievalUnavailableException exception) {
            return AskResultFactory.failure(
                    new AskFailure(AskFailureType.RETRIEVAL_UNAVAILABLE,
                            "retrieval dependency is unavailable",
                            Optional.of(exception.dependency())),
                    new AskExecutionMetadata(0, 0, 0, false));
        }

        AnswerContext context = contextAssembler.assemble(evidence, request.contextBudget());
        AskExecutionMetadata execution = new AskExecutionMetadata(evidence.items().size(),
                context.usage().usedEvidenceItems(), context.usage().usedCodePoints(),
                context.usage().truncated());
        List<AskCitation> suppliedEvidence = context.blocks().stream()
                .map(AskCitation::from).toList();

        if (evidence.insufficientEvidence() || context.blocks().isEmpty()) {
            return AskResultFactory.insufficient(suppliedEvidence, execution);
        }

        AnswerResult generated;
        try {
            generated = answerClient.generate(new org.km.llmwiki.ai.answer.AnswerRequest(
                    request.question(), context, request.generationOptions()));
        } catch (AnswerClientException exception) {
            return AskResultFactory.failure(
                    failureFor(exception), execution, suppliedEvidence);
        }

        if (generated == null) {
            return AskResultFactory.failure(
                    new AskFailure(AskFailureType.PROVIDER_INVALID_RESPONSE,
                    "answer provider returned no result"), execution, suppliedEvidence);
        }

        if (generated.answerText().codePointCount(0, generated.answerText().length())
                > request.generationOptions().maxOutputCodePoints()) {
            return AskResultFactory.failure(
                    new AskFailure(AskFailureType.PROVIDER_INVALID_RESPONSE,
                            "answer provider response exceeded the request output bound"),
                    execution, suppliedEvidence);
        }

        try {
            if (!generated.insufficientEvidence() && generated.citedEvidenceIds().isEmpty()) {
                throw new CitationValidationException(
                        "non-insufficient answers must cite at least one evidence item");
            }
            List<AskCitation> citations = mapCitations(context, generated.citedEvidenceIds());
            if (generated.insufficientEvidence()) {
                return AskResultFactory.insufficient(suppliedEvidence, execution);
            }
            return AskResultFactory.answered(generated, citations, suppliedEvidence, execution);
        } catch (CitationValidationException invalidGeneration) {
            return AskResultFactory.failure(
                    new AskFailure(AskFailureType.PROVIDER_INVALID_RESPONSE,
                            "answer provider response failed citation validation"),
                    execution, suppliedEvidence);
        }
    }

    private static List<AskCitation> mapCitations(AnswerContext context, List<String> citationIds) {
        List<String> normalized = context.normalizeCitationIds(citationIds);
        Set<String> accepted = Set.copyOf(normalized);
        // The provider's order is not authoritative. Context order is stable and deterministic.
        return context.blocks().stream()
                .filter(block -> accepted.contains(block.citationId()))
                .map(AskCitation::from)
                .toList();
    }

    private static AskFailure failureFor(AnswerClientException exception) {
        AnswerFailureType type = exception.failureType();
        AskFailureType askType = switch (type) {
            case CONFIGURATION_UNAVAILABLE_OR_DISABLED ->
                    AskFailureType.PROVIDER_CONFIGURATION_UNAVAILABLE;
            case AUTHENTICATION_OR_AUTHORIZATION ->
                    AskFailureType.PROVIDER_AUTHENTICATION_OR_AUTHORIZATION;
            case RATE_LIMIT_OR_QUOTA -> AskFailureType.PROVIDER_RATE_LIMIT_OR_QUOTA;
            case TIMEOUT_OR_NETWORK_UNAVAILABLE ->
                    AskFailureType.PROVIDER_TIMEOUT_OR_NETWORK_UNAVAILABLE;
            case PROVIDER_SERVER_FAILURE -> AskFailureType.PROVIDER_SERVER_FAILURE;
            case INVALID_PROVIDER_RESPONSE -> AskFailureType.PROVIDER_INVALID_RESPONSE;
            case LOCAL_VALIDATION -> AskFailureType.LOCAL_VALIDATION;
        };
        return new AskFailure(askType, exception.failure().diagnostic());
    }

    /** Small factory keeps the result invariants centralized and constructors readable. */
    private static final class AskResultFactory {
        private static AskResult answered(AnswerResult generated, List<AskCitation> citations,
                                           List<AskCitation> supplied, AskExecutionMetadata execution) {
            return new AskResult(AskStatus.ANSWERED, Optional.of(generated.answerText()), citations,
                    supplied, Optional.of(generated.providerMetadata()), generated.usage(),
                    Optional.empty(), execution);
        }

        private static AskResult insufficient(List<AskCitation> supplied,
                                              AskExecutionMetadata execution) {
            return new AskResult(AskStatus.INSUFFICIENT_EVIDENCE, Optional.empty(), List.of(),
                    supplied, Optional.empty(), Optional.empty(), Optional.empty(), execution);
        }

        private static AskResult failure(AskFailure failure, AskExecutionMetadata execution) {
            return failure(failure, execution, List.of());
        }

        private static AskResult failure(AskFailure failure, AskExecutionMetadata execution,
                                         List<AskCitation> supplied) {
            return new AskResult(AskStatus.FAILED, Optional.empty(), List.of(), supplied,
                    Optional.empty(), Optional.empty(), Optional.of(failure), execution);
        }
    }
}
