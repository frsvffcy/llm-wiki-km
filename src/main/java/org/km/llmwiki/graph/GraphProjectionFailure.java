package org.km.llmwiki.graph;

import org.km.llmwiki.web.DiagnosticRedaction;

/** Safe public failure data that never contains raw backend or canonical content. */
public record GraphProjectionFailure(GraphProjectionFailureType type, String diagnostic) {

    public static final int MAX_DIAGNOSTIC_LENGTH = 256;

    public GraphProjectionFailure {
        if (type == null) {
            throw new IllegalArgumentException("Graph projection failure type is required");
        }
        diagnostic = sanitize(diagnostic);
    }

    public static GraphProjectionFailure of(GraphProjectionFailureType type) {
        return new GraphProjectionFailure(type, type.publicCode());
    }

    public String publicCode() {
        return type.publicCode();
    }

    private static String sanitize(String value) {
        return DiagnosticRedaction.sanitize(value, "graph projection operation failed",
                MAX_DIAGNOSTIC_LENGTH);
    }
}
