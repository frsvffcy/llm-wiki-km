package org.km.llmwiki.web;

import org.km.llmwiki.ai.answer.ProviderUsageStatus;

import java.util.List;

/**
 * Operator-safe projection of one retrieval inspection. Carries only canonical identities,
 * modality-local ordinals, typed outcomes, disposition and stable reason codes, and budget
 * counts; raw scores, exception details, tokens, fingerprints, and paths never cross here.
 *
 * <p>Final evidence additionally carries a privacy-safe source projection (kind, navigation
 * identifiers, display label, inspection-time currentness) so Browser users can tell which
 * source an evidence item came from and open the canonical read-only locator/read view.
 * The identity stays the authority; the projection never carries paths, content, hashes,
 * or ranking inputs.
 */
public record RetrievalInspectionResponse(
        String query,
        String mode,
        String strategy,
        String fusionPolicyVersion,
        List<Modality> modalities,
        List<String> fusedOrder,
        List<Selection> selection,
        List<FinalEvidence> finalEvidence,
        ModalityDiagnostics modalityDiagnostics,
        int searchedCandidateCount,
        int rejectedCandidateCount,
        boolean insufficientEvidence,
        Budget budget,
        QueryTransformation queryTransformation,
        List<RetrievalInput> retrievalInputs) {

    public record Modality(String modality, String outcome,
                           List<Candidate> candidates, List<Rejected> rejected) {
    }

    public record Candidate(String identity, int ordinal) {
    }

    public record Rejected(String identity, String reason) {
    }

    public record Selection(String identity, String disposition, String reason) {
    }

    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record FinalEvidence(int ordinal, String identity, String kind, Long sourceChunkId,
                                String knowledgeId, String displayLabel, String currentness) {
        /** Compatibility constructor for projections built before the source projection. */
        public FinalEvidence(int ordinal, String identity) {
            this(ordinal, identity, null, null, null, null, null);
        }
    }

    public record ModalityDiagnostics(String lexical, String vector, String graph) {
    }

    public record Budget(int maxItems, int maxCharacters, int usedItems, int usedCharacters,
                         int estimatedTokens, boolean truncated) {
    }

    public record QueryTransformation(String policyVersion, String status, String applicability,
                                      ProviderUsageStatus providerUsageStatus,
                                      int retrievalInputCount, Long providerLatencyMs,
                                      Integer providerInputTokens, Integer providerOutputTokens,
                                      Integer providerTotalTokens) {
    }

    public record RetrievalInput(int ordinal, String role, String query,
                                 List<Modality> modalities, List<String> fusedOrder,
                                 List<Selection> selection) {
    }
}
