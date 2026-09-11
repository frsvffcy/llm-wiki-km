package org.km.llmwiki.ai.ask;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.km.llmwiki.ai.answer.AnswerContextProvenance;
import org.km.llmwiki.ai.answer.AnswerProviderMetadata;
import org.km.llmwiki.ai.answer.AnswerContextDiagnostics;
import org.km.llmwiki.rag.RerankNoOpReason;
import org.km.llmwiki.rag.RerankStatus;
import org.km.llmwiki.ai.answer.ContextProjectionFailureType;
import org.km.llmwiki.ai.answer.ProjectionKind;
import org.km.llmwiki.ai.answer.ProviderUsageStatus;
import org.km.llmwiki.rag.EvidenceKind;
import org.km.llmwiki.rag.RetrievalDiagnostics;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Safe, provider-neutral response projection for the Ask REST API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AskApiResponse(
        AskStatus status,
        String answer,
        boolean insufficientEvidence,
        List<Citation> citations,
        ProviderMetadata providerMetadata,
        ExecutionMetadata executionMetadata,
        RetrievalMetadata retrievalMetadata
) {

    private static final Pattern SAFE_CONTEXT_POLICY_VERSION =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    public AskApiResponse(AskStatus status, String answer, boolean insufficientEvidence,
                          List<Citation> citations, ProviderMetadata providerMetadata,
                          ExecutionMetadata executionMetadata) {
        this(status, answer, insufficientEvidence, citations, providerMetadata,
                executionMetadata, null);
    }

    public AskApiResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    static AskApiResponse from(AskResult result) {
        return new AskApiResponse(
                result.status(),
                result.answerText().orElse(null),
                result.insufficientEvidence(),
                result.citations().stream().map(Citation::from).toList(),
                result.providerMetadata().map(ProviderMetadata::from).orElse(null),
                ExecutionMetadata.from(result.executionMetadata()),
                RetrievalMetadata.from(result.retrievalDiagnostics()));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Citation(String citationId, EvidenceKind evidenceKind, Provenance provenance) {
        static Citation from(AskCitation citation) {
            return new Citation(citation.citationId(), citation.evidenceKind(),
                    Provenance.from(citation.provenance()));
        }
    }

    /** Human-usable provenance; it deliberately has no internal content or hash fields. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Provenance(
            String type,
            String title,
            String path,
            Integer revision,
            String documentName,
            Long documentId,
            Long sourceChunkId,
            Integer chunkNo,
            Integer pageNo,
            String section,
            String headingPath
    ) {
        static Provenance from(AnswerContextProvenance provenance) {
            return switch (provenance) {
                case AnswerContextProvenance.Wiki wiki -> new Provenance(
                        "WIKI", wiki.title(), safeWikiPath(wiki.path()), wiki.revision(),
                        null, null, null, null, null, null, null);
                case AnswerContextProvenance.Source source -> new Provenance(
                        "SOURCE", null, null, null, source.documentName(), source.documentId(),
                        source.sourceChunkId(), source.chunkNo(), source.pageNo(), source.section(),
                        source.headingPath());
            };
        }

        private static String safeWikiPath(String path) {
            if (path == null || path.isBlank() || path.indexOf('\0') >= 0
                    || path.startsWith("/") || path.startsWith("\\")
                    || path.matches("^[A-Za-z]:[\\\\/].*")) {
                return null;
            }
            String normalized = path.replace('\\', '/');
            if (normalized.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
                return null;
            }
            for (String segment : normalized.split("/", -1)) {
                if (segment.equals("..")) {
                    return null;
                }
            }
            return normalized;
        }
    }

    public record ProviderMetadata(String provider, String model) {
        static ProviderMetadata from(AnswerProviderMetadata metadata) {
            return new ProviderMetadata(metadata.provider(), metadata.model());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExecutionMetadata(
            int retrievedEvidenceItems,
            int contextEvidenceItems,
            int contextCodePoints,
            boolean contextTruncated,
            ContextDiagnostics contextDiagnostics,
            String rerankPolicyVersion,
            RerankStatus rerankStatus,
            RerankNoOpReason rerankNoOpReason
    ) {
        static ExecutionMetadata from(AskExecutionMetadata metadata) {
            return new ExecutionMetadata(metadata.retrievedEvidenceItems(),
                    metadata.contextEvidenceItems(), metadata.contextCodePoints(),
                    metadata.contextTruncated(),
                    ContextDiagnostics.from(metadata.contextDiagnostics()),
                    metadata.rerankPolicyVersion(), metadata.rerankStatus(),
                    metadata.rerankNoOpReason());
        }
    }

    /** Safe context lifecycle and provider-measurement projection; never contains content. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContextDiagnostics(
            int retrievedEvidenceCount,
            int admittedEvidenceCount,
            int answerContextBlockCount,
            int originalCodePoints,
            int packedCodePoints,
            int projectedCodePoints,
            double reductionRatio,
            boolean truncated,
            boolean compacted,
            String contextPolicyVersion,
            Map<ProjectionKind, Integer> projectionKindDistribution,
            boolean projectionFallbackUsed,
            ContextProjectionFailureType projectionFailureType,
            Long projectionLatencyMs,
            Long answerLatencyMs,
            ProviderUsageStatus providerUsageStatus,
            Integer providerInputTokens,
            Integer providerOutputTokens,
            Integer providerTotalTokens
    ) {
        static ContextDiagnostics from(AnswerContextDiagnostics diagnostics) {
            return new ContextDiagnostics(
                    diagnostics.retrievedEvidenceCount(),
                    diagnostics.admittedEvidenceCount(),
                    diagnostics.answerContextBlockCount(),
                    diagnostics.originalCodePoints(),
                    diagnostics.packedCodePoints(),
                    diagnostics.projectedCodePoints(),
                    diagnostics.reductionRatio(),
                    diagnostics.truncated(),
                    diagnostics.compacted(),
                    safeContextPolicyVersion(diagnostics.contextPolicyVersion()),
                    diagnostics.projectionKindDistribution(),
                    diagnostics.projectionFallbackUsed(),
                    diagnostics.projectionFailureType(),
                    diagnostics.projectionLatencyMs(),
                    diagnostics.answerLatencyMs(),
                    diagnostics.providerUsageStatus(),
                    diagnostics.providerInputTokens(),
                    diagnostics.providerOutputTokens(),
                    diagnostics.providerTotalTokens());
        }

        private static String safeContextPolicyVersion(String value) {
            return value != null && SAFE_CONTEXT_POLICY_VERSION.matcher(value).matches()
                    ? value : null;
        }
    }

    /** Safe retrieval signal summary; excludes scores, reasons, vectors, and provider details. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RetrievalMetadata(
            String strategy,
            boolean lexicalSignalUsed,
            boolean vectorSignalUsed,
            boolean degradedFallback,
            boolean vectorUnavailable,
            boolean graphSignalUsed,
            boolean graphDegraded,
            boolean graphUnavailable
    ) {
        static RetrievalMetadata from(RetrievalDiagnostics diagnostics) {
            if (diagnostics == null) return null;
            return new RetrievalMetadata(diagnostics.strategy().name(),
                    diagnostics.lexicalSignalUsed(), diagnostics.vectorSignalUsed(),
                    diagnostics.degradedFallback(), diagnostics.vectorUnavailable(),
                    diagnostics.graphSignalUsed(), diagnostics.graphDegraded(),
                    diagnostics.graphUnavailable());
        }
    }
}
