package org.km.llmwiki.graph;

import java.text.Normalizer;

/** Workspace-scoped pointer to canonical authority; it is not itself a citation or graph ID. */
public record GraphAuthorityReference(GraphWorkspaceScope workspace, GraphAuthorityKind kind,
                                      String stableId) {

    public static final int MAX_STABLE_ID_CODE_POINTS = 256;

    public GraphAuthorityReference {
        if (workspace == null || kind == null) {
            throw new IllegalArgumentException("Graph authority workspace and kind are required");
        }
        stableId = normalize(stableId);
        if (stableId.codePointCount(0, stableId.length()) > MAX_STABLE_ID_CODE_POINTS) {
            throw new IllegalArgumentException("Graph authority stable id is too long");
        }
    }

    public static GraphAuthorityReference of(GraphWorkspaceScope workspace, String kind,
                                             String stableId) {
        return new GraphAuthorityReference(workspace, GraphAuthorityKind.fromWireValue(kind), stableId);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Graph authority stable id must not be blank");
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC).trim();
    }
}
