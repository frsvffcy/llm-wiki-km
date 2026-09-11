package org.km.llmwiki.ai.ask;

import org.km.llmwiki.ai.answer.AnswerClient;
import org.km.llmwiki.ai.answer.AnswerClientException;
import org.km.llmwiki.ai.answer.AnswerContext;
import org.km.llmwiki.ai.answer.AnswerContextDiagnostics;
import org.km.llmwiki.ai.answer.ContextProjectionResult;
import org.km.llmwiki.ai.answer.EvidenceContextProjector;
import org.km.llmwiki.ai.answer.AnswerResult;
import org.km.llmwiki.ai.answer.AnswerFailureType;
import org.km.llmwiki.ai.answer.AnswerProviderMetadata;
import org.km.llmwiki.ai.answer.AnswerUsageMetadata;
import org.km.llmwiki.ai.answer.CitationValidationException;
import org.km.llmwiki.ai.answer.ProviderUsageStatus;
import org.km.llmwiki.rag.EvidenceBundle;
import org.km.llmwiki.rag.RetrievalService;
import org.km.llmwiki.rag.RetrievalUnavailableException;
import org.km.llmwiki.rag.RetrievalDiagnostics;
import org.km.llmwiki.rag.RetrievalStrategy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Single application-level Ask orchestration boundary.
 *
 * <p>Retrieval owns candidate search and authority revalidation, the evidence context
 * projector owns the single context-packing boundary (baseline assembly plus the active
 * versioned compaction policy), and AnswerClient owns grounded prompt/provider response
 * validation. This service coordinates those contracts without introducing controller,
 * session, or agent responsibilities.
 */
@Service
public class AskService {

    private final RetrievalService retrievalService;
    private final EvidenceContextProjector contextProjector;
    private final org.km.llmwiki.rag.SecondStageRerankService rerankService;
    private final AnswerClient answerClient;

    public AskService(RetrievalService retrievalService, EvidenceContextProjector contextProjector,
                      org.km.llmwiki.rag.SecondStageRerankService rerankService,
                      AnswerClient answerClient) {
        this.retrievalService = retrievalService;
        this.contextProjector = contextProjector;
        this.rerankService = rerankService;
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
                    new AskFailure(retrievalFailureType(exception),
                            "retrieval dependency is unavailable",
                            Optional.of(exception.dependency())),
                    AskExecutionMetadata.fromDiagnostics(AnswerContextDiagnostics.empty()),
                    List.of(), retrievalFailureDiagnostics(request, exception));
        }

        // Second-stage reranking reorders the already-qualified evidence into an ordered
        // view; the policy can never add, drop, or re-identify evidence (blocking invariants
        // enforced inside the rerank execution boundary), and the existing retrieval-side
        // terminal/Ask handoff currentness guard stays authoritative for this consumption
        // window because the reorder is a pure in-memory view with no new reads.
        org.km.llmwiki.rag.RerankResult rerank = rerankService.apply(evidence);
        EvidenceBundle qualified = rerank.orderedBundle();

        long projectionStarted = System.nanoTime();
        ContextProjectionResult projection = contextProjector.project(qualified,
                request.contextBudget());
        long projectionLatencyMs = elapsedMillis(projectionStarted);
        AnswerContext context = projection.context();
        AnswerContextDiagnostics contextDiagnostics = AnswerContextDiagnostics.from(qualified,
                projection, projectionLatencyMs, null, ProviderUsageStatus.NOT_ATTEMPTED, null);
        AskExecutionMetadata execution = AskExecutionMetadata.fromDiagnostics(contextDiagnostics)
                .withRerankOutcome(rerank.policyVersion(), rerank.status(), rerank.noOpReason());
        List<AskCitation> suppliedEvidence = context.blocks().stream()
                .map(AskCitation::from).toList();

        if (evidence.insufficientEvidence() || context.blocks().isEmpty()) {
            return AskResultFactory.insufficient(suppliedEvidence, execution,
                    evidence.diagnostics());
        }

        AnswerResult generated;
        long answerStarted = System.nanoTime();
        try {
            generated = answerClient.generate(new org.km.llmwiki.ai.answer.AnswerRequest(
                    request.question(), context, request.generationOptions()));
        } catch (AnswerClientException exception) {
            execution = execution.withProviderOutcome(ProviderUsageStatus.UNAVAILABLE, null,
                    elapsedMillis(answerStarted));
            return AskResultFactory.failure(
                    failureFor(exception), execution, suppliedEvidence, evidence.diagnostics(),
                    null, Optional.empty());
        }

        long answerLatencyMs = elapsedMillis(answerStarted);
        if (generated == null) {
            execution = execution.withProviderOutcome(ProviderUsageStatus.UNAVAILABLE, null,
                    answerLatencyMs);
            return AskResultFactory.failure(
                    new AskFailure(AskFailureType.PROVIDER_INVALID_RESPONSE,
                    "answer provider returned no result"), execution, suppliedEvidence,
                    evidence.diagnostics(), null, Optional.empty());
        }

        Optional<AnswerUsageMetadata> usage = generated.usage();
        AnswerProviderMetadata providerMetadata = generated.providerMetadata();
        ProviderUsageStatus usageStatus = usage.isPresent()
                ? ProviderUsageStatus.AVAILABLE : ProviderUsageStatus.UNAVAILABLE;
        execution = execution.withProviderOutcome(usageStatus, usage.orElse(null),
                answerLatencyMs);

        if (generated.answerText().codePointCount(0, generated.answerText().length())
                > request.generationOptions().maxOutputCodePoints()) {
            return AskResultFactory.failure(
                    new AskFailure(AskFailureType.PROVIDER_INVALID_RESPONSE,
                            "answer provider response exceeded the request output bound"),
                    execution, suppliedEvidence, evidence.diagnostics(), providerMetadata, usage);
        }

        try {
            List<AskCitation> citations = mapCitations(context, generated.citedEvidenceIds());
            if (generated.insufficientEvidence()) {
                return AskResultFactory.insufficient(suppliedEvidence, execution,
                        evidence.diagnostics(), providerMetadata, usage);
            }
            return AskResultFactory.answered(generated, citations, suppliedEvidence, execution,
                    evidence.diagnostics());
        } catch (CitationValidationException invalidGeneration) {
            return AskResultFactory.failure(
                    new AskFailure(AskFailureType.PROVIDER_INVALID_RESPONSE,
                            "answer provider response failed citation validation"),
                    execution, suppliedEvidence, evidence.diagnostics(), providerMetadata, usage);
        }
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static AskFailureType retrievalFailureType(RetrievalUnavailableException exception) {
        return exception.dependency() == RetrievalUnavailableException.Dependency.VECTOR_SEARCH
                ? AskFailureType.RETRIEVAL_VECTOR_UNAVAILABLE
                : AskFailureType.RETRIEVAL_UNAVAILABLE;
    }

    private static RetrievalDiagnostics retrievalFailureDiagnostics(AskRequest request,
                                                                     RetrievalUnavailableException exception) {
        RetrievalStrategy strategy = request.retrievalRequest().strategy();
        if (exception.dependency() != RetrievalUnavailableException.Dependency.VECTOR_SEARCH) {
            return switch (strategy) {
                case LEXICAL -> RetrievalDiagnostics.lexical();
                case SEMANTIC -> RetrievalDiagnostics.semantic();
                case HYBRID -> RetrievalDiagnostics.hybrid();
                case FUSED -> RetrievalDiagnostics.fused();
            };
        }
        return switch (strategy) {
            case LEXICAL -> RetrievalDiagnostics.lexical();
            case SEMANTIC -> RetrievalDiagnostics.unavailableSemantic(exception.getMessage());
            case HYBRID -> RetrievalDiagnostics.degradedHybrid(exception.getMessage());
            case FUSED -> RetrievalDiagnostics.fused();
        };
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
                                           List<AskCitation> supplied, AskExecutionMetadata execution,
                                           RetrievalDiagnostics diagnostics) {
            return new AskResult(AskStatus.ANSWERED, Optional.of(generated.answerText()), citations,
                    supplied, Optional.of(generated.providerMetadata()), generated.usage(),
                    Optional.empty(), execution, diagnostics);
        }

        private static AskResult insufficient(List<AskCitation> supplied,
                                              AskExecutionMetadata execution,
                                              RetrievalDiagnostics diagnostics) {
            return insufficient(supplied, execution, diagnostics, null, Optional.empty());
        }

        private static AskResult insufficient(List<AskCitation> supplied,
                                              AskExecutionMetadata execution,
                                              RetrievalDiagnostics diagnostics,
                                              AnswerProviderMetadata providerMetadata,
                                              Optional<AnswerUsageMetadata> usage) {
            return new AskResult(AskStatus.INSUFFICIENT_EVIDENCE, Optional.empty(), List.of(),
                    supplied, Optional.ofNullable(providerMetadata), usage, Optional.empty(),
                    execution, diagnostics);
        }

        private static AskResult failure(AskFailure failure, AskExecutionMetadata execution) {
            return failure(failure, execution, List.of());
        }

        private static AskResult failure(AskFailure failure, AskExecutionMetadata execution,
                                         List<AskCitation> supplied,
                                         RetrievalDiagnostics diagnostics) {
            return failure(failure, execution, supplied, diagnostics, null, Optional.empty());
        }

        private static AskResult failure(AskFailure failure, AskExecutionMetadata execution,
                                         List<AskCitation> supplied,
                                         RetrievalDiagnostics diagnostics,
                                         AnswerProviderMetadata providerMetadata,
                                         Optional<AnswerUsageMetadata> usage) {
            return new AskResult(AskStatus.FAILED, Optional.empty(), List.of(), supplied,
                    Optional.ofNullable(providerMetadata), usage, Optional.of(failure),
                    execution, diagnostics);
        }

        private static AskResult failure(AskFailure failure, AskExecutionMetadata execution,
                                         List<AskCitation> supplied) {
            return failure(failure, execution, supplied, RetrievalDiagnostics.lexical());
        }
    }
}
