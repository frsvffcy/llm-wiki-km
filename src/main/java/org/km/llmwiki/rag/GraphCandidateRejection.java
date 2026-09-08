package org.km.llmwiki.rag;

import org.km.llmwiki.graph.GraphEntityIdentity;

/** One typed candidate rejection; diagnostics only, never a stale evidence substitute. */
public record GraphCandidateRejection(GraphEntityIdentity entity,
                                      GraphEvidenceRejectionReason reason,
                                      String detail) {

    public static final int MAX_DETAIL_CODE_POINTS = 256;

    public GraphCandidateRejection {
        if (entity == null || reason == null) {
            throw new IllegalArgumentException("Graph candidate rejection requires entity and reason");
        }
        detail = bound(detail);
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
