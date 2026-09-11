package org.km.llmwiki.web;

import org.km.llmwiki.rag.RetrievalInspectionReport;
import org.km.llmwiki.rag.RetrievalMode;
import org.km.llmwiki.rag.RetrievalRequest;

/**
 * Shared application-facing Retrieval Inspector boundary used by the REST and MCP adapters.
 *
 * <p>Input validation and the safe report projection live here in one implementation so both
 * adapters observe identical inspector semantics without calling each other's adapter classes.
 * Final evidence, rejection, and degradation semantics stay in
 * {@link org.km.llmwiki.rag.RetrievalInspectorService}; this mapper adds no retrieval, fusion,
 * authority, or currentness policy of its own, never mutates canonical state, and never calls
 * an answer provider.
 *
 * <p>This mapper never depends on servlet/HTTP/MCP types, and it never wraps results in a
 * transport envelope — the REST {@code ApiResponse} envelope and the MCP JSON-RPC mapping
 * each remain the responsibility of their own adapter.
 */
public final class RetrievalInspectionMapper {

    private static final int MAX_QUESTION_CODE_POINTS = 4_000;

    private RetrievalInspectionMapper() {
    }

    /**
     * Shared strict query validation: a raw question plus mode becomes a validated
     * {@link RetrievalRequest} with identical rules for every adapter (blank/strip/4000
     * code-point question bound, required retrieval mode).
     */
    public static RetrievalRequest validate(String question, String mode) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        String normalized = question.strip();
        if (normalized.codePointCount(0, normalized.length()) > MAX_QUESTION_CODE_POINTS) {
            throw new IllegalArgumentException("question must not exceed 4000 Unicode code points");
        }
        RetrievalMode retrievalMode;
        try {
            retrievalMode = mode == null ? null : RetrievalMode.valueOf(mode);
        } catch (IllegalArgumentException malformedMode) {
            throw new IllegalArgumentException("unknown retrieval mode: requested mode is not supported");
        }
        if (retrievalMode == null) {
            throw new IllegalArgumentException("retrieval mode is required");
        }
        return RetrievalRequest.defaults(normalized, retrievalMode);
    }

    /** Shared safe projection: an application report becomes the transport-neutral response. */
    public static RetrievalInspectionResponse toResponse(RetrievalInspectionReport report) {
        return new RetrievalInspectionResponse(
                report.query(),
                report.mode().name(),
                report.strategy().name(),
                report.fusionPolicyVersion(),
                report.modalities().stream().map(section -> new RetrievalInspectionResponse.Modality(
                        section.modality().name(),
                        section.outcome().name(),
                        section.candidates().stream().map(candidate ->
                                new RetrievalInspectionResponse.Candidate(candidate.identity(),
                                        candidate.ordinal())).toList(),
                        section.rejected().stream().map(rejected ->
                                new RetrievalInspectionResponse.Rejected(rejected.identity(),
                                        rejected.reasonCode())).toList())).toList(),
                report.fusedOrder(),
                report.selection().stream().map(trace -> new RetrievalInspectionResponse.Selection(
                        trace.identity(), trace.disposition().name(), trace.reasonCode())).toList(),
                report.finalEvidence().stream().map(evidence ->
                        new RetrievalInspectionResponse.FinalEvidence(evidence.ordinal(),
                                evidence.identity())).toList(),
                new RetrievalInspectionResponse.ModalityDiagnostics(
                        report.modalityDiagnostics().lexical().name(),
                        report.modalityDiagnostics().vector().name(),
                        report.modalityDiagnostics().graph().name()),
                report.searchedCandidateCount(),
                report.rejectedCandidateCount(),
                report.insufficientEvidence(),
                new RetrievalInspectionResponse.Budget(
                        report.budget().maxItems(),
                        report.budget().maxCharacters(),
                        report.budget().usedItems(),
                        report.budget().usedCharacters(),
                        report.budget().estimatedTokens(),
                        report.budget().truncated()));
    }
}
