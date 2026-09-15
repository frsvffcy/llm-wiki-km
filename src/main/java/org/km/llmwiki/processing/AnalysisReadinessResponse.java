package org.km.llmwiki.processing;

/**
 * Workspace-scoped, read-only projection of Document Analysis prerequisites (issue #448).
 *
 * <p>Distinguishes filesystem readiness from feature readiness without binding the optional
 * analysis capability to overall application availability: {@code workspaceReady} reflects the
 * workspace root, while {@code analysisReady} requires a readable prompt template and valid
 * settings. All diagnostics are allow-listed and operator-safe; no absolute path, secret,
 * or provider payload ever leaves this contract.
 */
public record AnalysisReadinessResponse(
        long workspaceId,
        boolean workspaceReady,
        String promptStatus,
        String promptErrorCode,
        String promptErrorMessage,
        boolean settingsValid,
        String settingsErrorCode,
        String provider,
        String model,
        Integer maximumEvidenceChunks,
        boolean analysisReady) {
}
