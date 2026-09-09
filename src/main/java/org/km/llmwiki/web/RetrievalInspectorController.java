package org.km.llmwiki.web;

import org.km.llmwiki.rag.RetrievalInspectionReport;
import org.km.llmwiki.rag.RetrievalInspectorService;
import org.km.llmwiki.rag.RetrievalMode;
import org.km.llmwiki.rag.RetrievalRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adapter-only read-only Retrieval Inspector endpoint. All retrieval, fusion, authority, and
 * currentness policy lives in the application boundary; this controller validates public input
 * and maps the safe report projection. The inspect flow never mutates canonical state and never
 * calls an answer provider.
 */
@RestController
@RequestMapping("/api/v1/retrieval")
public class RetrievalInspectorController {

    private static final int MAX_QUESTION_CODE_POINTS = 4_000;

    private final RetrievalInspectorService inspectorService;

    public RetrievalInspectorController(RetrievalInspectorService inspectorService) {
        this.inspectorService = inspectorService;
    }

    @GetMapping("/inspect")
    public ApiResponse<RetrievalInspectionResponse> inspect(
            @RequestParam(required = false) String question,
            @RequestParam(required = false) String mode) {
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
        RetrievalInspectionReport report = inspectorService.inspect(
                RetrievalRequest.defaults(normalized, retrievalMode));
        return new ApiResponse<>(toResponse(report));
    }

    private static RetrievalInspectionResponse toResponse(RetrievalInspectionReport report) {
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
