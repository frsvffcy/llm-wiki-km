package org.km.llmwiki.web;

import java.util.List;

/**
 * Operator-safe projection of one retrieval inspection. Carries only canonical identities,
 * modality-local ordinals, typed outcomes, disposition and stable reason codes, and budget
 * counts; raw scores, exception details, tokens, fingerprints, and paths never cross here.
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
        Budget budget) {

    public record Modality(String modality, String outcome,
                           List<Candidate> candidates, List<Rejected> rejected) {
    }

    public record Candidate(String identity, int ordinal) {
    }

    public record Rejected(String identity, String reason) {
    }

    public record Selection(String identity, String disposition, String reason) {
    }

    public record FinalEvidence(int ordinal, String identity) {
    }

    public record ModalityDiagnostics(String lexical, String vector, String graph) {
    }

    public record Budget(int maxItems, int maxCharacters, int usedItems, int usedCharacters,
                         int estimatedTokens, boolean truncated) {
    }
}
