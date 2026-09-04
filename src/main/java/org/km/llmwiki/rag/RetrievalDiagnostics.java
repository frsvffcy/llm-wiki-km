package org.km.llmwiki.rag;

/** Explicit signal availability semantics carried with an authoritative retrieval result. */
public record RetrievalDiagnostics(
        RetrievalStrategy strategy,
        boolean lexicalSignalUsed,
        boolean vectorSignalUsed,
        boolean degradedFallback,
        boolean vectorUnavailable,
        String vectorUnavailableReason
) {
    public RetrievalDiagnostics {
        if (strategy == null) {
            throw new IllegalArgumentException("retrieval strategy is required");
        }
        if (!vectorUnavailable && vectorUnavailableReason != null) {
            throw new IllegalArgumentException("vector unavailable reason requires unavailable state");
        }
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
}
