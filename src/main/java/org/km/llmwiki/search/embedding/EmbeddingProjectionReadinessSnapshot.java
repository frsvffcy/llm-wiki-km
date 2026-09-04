package org.km.llmwiki.search.embedding;

/**
 * Immutable serving proof captured before a semantic query starts its provider call.
 *
 * <p>This is a query boundary over the persisted #214 readiness proof. It is deliberately not
 * an operation or lifecycle model: a query may use the proof only while every field still
 * matches the current workspace/corpus readiness row.
 */
public record EmbeddingProjectionReadinessSnapshot(
        long workspaceId,
        EmbeddingEvidenceKind corpus,
        long targetGeneration,
        long appliedGeneration,
        String projectionSnapshotToken,
        String provider,
        String model,
        int dimension,
        String projectionVersion) {

    public EmbeddingProjectionReadinessSnapshot {
        if (workspaceId <= 0 || corpus == null || targetGeneration <= 0 || appliedGeneration <= 0
                || projectionSnapshotToken == null || projectionSnapshotToken.isBlank()
                || provider == null || provider.isBlank() || model == null || model.isBlank()
                || dimension <= 0 || projectionVersion == null || projectionVersion.isBlank()) {
            throw new IllegalArgumentException("Embedding readiness snapshot is incomplete");
        }
        projectionSnapshotToken = projectionSnapshotToken.trim();
        provider = provider.trim();
        model = model.trim();
        projectionVersion = projectionVersion.trim();
    }

    public static EmbeddingProjectionReadinessSnapshot from(EmbeddingProjectionReadiness state) {
        if (state == null || state.status() != EmbeddingProjectionReadinessStatus.READY
                || state.targetGeneration() != state.appliedGeneration()
                || state.dimension() == null) {
            throw new IllegalArgumentException("Embedding readiness is not a complete READY proof");
        }
        return new EmbeddingProjectionReadinessSnapshot(state.workspaceId(), state.corpus(),
                state.targetGeneration(), state.appliedGeneration(), state.projectionSnapshotToken(),
                state.provider(), state.model(), state.dimension(), state.projectionVersion());
    }

    public boolean matches(EmbeddingProjectionReadiness state) {
        return state != null
                && state.workspaceId() == workspaceId
                && state.corpus() == corpus
                && state.status() == EmbeddingProjectionReadinessStatus.READY
                && state.targetGeneration() == targetGeneration
                && state.appliedGeneration() == appliedGeneration
                && java.util.Objects.equals(state.projectionSnapshotToken(), projectionSnapshotToken)
                && java.util.Objects.equals(state.provider(), provider)
                && java.util.Objects.equals(state.model(), model)
                && java.util.Objects.equals(state.dimension(), dimension)
                && java.util.Objects.equals(state.projectionVersion(), projectionVersion);
    }
}
