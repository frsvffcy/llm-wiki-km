package org.km.llmwiki.rag;

/**
 * Typed, bounded diagnostics for one fused retrieval. Details are optional, bounded strings; they
 * never carry vendor internals, raw backend messages with credentials, or unbounded text.
 */
public record FusedModalityDiagnostics(ModalityOutcome lexical, ModalityOutcome vector,
                                       ModalityOutcome graph, String vectorDetail,
                                       String graphDetail, int terminalRejectedCount) {

    public static final int MAX_DETAIL_CODE_POINTS = 256;

    public FusedModalityDiagnostics {
        if (lexical == null || vector == null || graph == null) {
            throw new IllegalArgumentException("Fusion modality outcomes are required");
        }
        vectorDetail = bound(vectorDetail);
        graphDetail = bound(graphDetail);
        if (terminalRejectedCount < 0) {
            throw new IllegalArgumentException("Terminal rejection count must not be negative");
        }
    }

    private static String bound(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.codePointCount(0, stripped.length()) <= MAX_DETAIL_CODE_POINTS
                ? stripped
                : stripped.substring(0, stripped.offsetByCodePoints(0, MAX_DETAIL_CODE_POINTS));
    }
}
