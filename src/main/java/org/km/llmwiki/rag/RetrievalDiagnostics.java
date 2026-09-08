package org.km.llmwiki.rag;

/**
 * Explicit signal availability semantics carried with an authoritative retrieval result. A
 * degraded or unavailable signal is a typed diagnostic, never a silent zero result: one
 * signal's infrastructure failure must not be reported as insufficient evidence of the whole
 * retrieval, and a degraded graph signal must stay distinguishable from a normal empty result.
 */
public record RetrievalDiagnostics(
        RetrievalStrategy strategy,
        boolean lexicalSignalUsed,
        boolean vectorSignalUsed,
        boolean degradedFallback,
        boolean vectorUnavailable,
        String vectorUnavailableReason,
        boolean graphSignalUsed,
        boolean graphDegraded,
        boolean graphUnavailable,
        String graphDetail
) {
    public RetrievalDiagnostics {
        if (strategy == null) {
            throw new IllegalArgumentException("retrieval strategy is required");
        }
        if (!vectorUnavailable && vectorUnavailableReason != null) {
            throw new IllegalArgumentException("vector unavailable reason requires unavailable state");
        }
        if (!graphDegraded && !graphUnavailable && graphDetail != null) {
            throw new IllegalArgumentException("graph detail requires a degraded or unavailable graph signal");
        }
    }

    /** Compatibility view for callers that predate the graph-grounded fused mode. */
    public RetrievalDiagnostics(RetrievalStrategy strategy, boolean lexicalSignalUsed,
                                boolean vectorSignalUsed, boolean degradedFallback,
                                boolean vectorUnavailable, String vectorUnavailableReason) {
        this(strategy, lexicalSignalUsed, vectorSignalUsed, degradedFallback, vectorUnavailable,
                vectorUnavailableReason, false, false, false, null);
    }

    public static RetrievalDiagnostics lexical() {
        return new RetrievalDiagnostics(RetrievalStrategy.LEXICAL, true, false,
                false, false, null);
    }

    public static RetrievalDiagnostics semantic() {
        return new RetrievalDiagnostics(RetrievalStrategy.SEMANTIC, false, true,
                false, false, null);
    }

    /** Semantic retrieval cannot complete when its required vector signal is unavailable. */
    public static RetrievalDiagnostics unavailableSemantic(String reason) {
        return new RetrievalDiagnostics(RetrievalStrategy.SEMANTIC, false, false,
                false, true,
                reason == null ? "vector candidate search unavailable" : reason);
    }

    public static RetrievalDiagnostics hybrid() {
        return new RetrievalDiagnostics(RetrievalStrategy.HYBRID, true, true,
                false, false, null);
    }

    public static RetrievalDiagnostics degradedHybrid(String reason) {
        return new RetrievalDiagnostics(RetrievalStrategy.HYBRID, true, false,
                true, true, reason == null ? "vector candidate search unavailable" : reason);
    }

    /** Signal composition summary of the graph-grounded fused mode for failure diagnostics. */
    public static RetrievalDiagnostics fused() {
        return new RetrievalDiagnostics(RetrievalStrategy.FUSED, true, true,
                false, false, null, true, false, false, null);
    }

    /**
     * Maps typed fusion modality outcomes into the retrieval diagnostics contract. A degraded
     * graph channel stays diagnosable: it completed and contributed to ranking, so it is
     * reported as used and degraded — never as a normal zero result. An unavailable or
     * not-ready projection is reported as an unavailable graph signal, and an unavailable
     * vector signal keeps its typed unavailable flag and reason.
     */
    public static RetrievalDiagnostics fused(FusedModalityDiagnostics fusionDiagnostics) {
        ModalityOutcome vector = fusionDiagnostics.vector();
        ModalityOutcome graph = fusionDiagnostics.graph();
        boolean graphDegraded = graph == ModalityOutcome.DEGRADED;
        boolean graphUnavailable = graph == ModalityOutcome.UNAVAILABLE
                || graph == ModalityOutcome.NOT_READY;
        String graphDetail = graphDegraded || graphUnavailable
                ? fusionDiagnostics.graphDetail() : null;
        boolean vectorUnavailable = vector == ModalityOutcome.UNAVAILABLE;
        String vectorReason = vectorUnavailable
                ? (fusionDiagnostics.vectorDetail() == null
                ? "vector candidate search unavailable" : fusionDiagnostics.vectorDetail())
                : null;
        return new RetrievalDiagnostics(RetrievalStrategy.FUSED, true,
                !vectorUnavailable, false, vectorUnavailable, vectorReason,
                graph == ModalityOutcome.CONTRIBUTED || graph == ModalityOutcome.EMPTY
                        || graphDegraded,
                graphDegraded, graphUnavailable, graphDetail);
    }
}
