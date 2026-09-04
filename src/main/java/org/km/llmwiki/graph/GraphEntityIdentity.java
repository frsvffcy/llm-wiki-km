package org.km.llmwiki.graph;

import java.text.Normalizer;

/** Application-owned stable identity for one workspace-scoped graph entity. */
public record GraphEntityIdentity(GraphWorkspaceScope workspace, GraphEntityType type,
                                  String canonicalKey, String stableId) {

    public static final int MAX_CANONICAL_KEY_CODE_POINTS = 256;

    public GraphEntityIdentity(GraphWorkspaceScope workspace, GraphEntityType type,
                               String canonicalKey) {
        this(workspace, type, canonicalKey,
                GraphIdentityCodec.entityStableId(workspace, type, normalizeKey(canonicalKey)));
    }

    public GraphEntityIdentity {
        if (workspace == null || type == null) {
            throw new IllegalArgumentException("Graph entity workspace and type are required");
        }
        canonicalKey = normalizeKey(canonicalKey);
        if (canonicalKey.codePointCount(0, canonicalKey.length()) > MAX_CANONICAL_KEY_CODE_POINTS) {
            throw new IllegalArgumentException("Graph canonical identity is too long");
        }
        String expected = GraphIdentityCodec.entityStableId(workspace, type, canonicalKey);
        if (stableId == null || !expected.equals(stableId)) {
            throw new IllegalArgumentException("Graph entity stable id does not match canonical identity");
        }
    }

    public static GraphEntityIdentity of(GraphWorkspaceScope workspace, GraphEntityType type,
                                         String canonicalKey) {
        return new GraphEntityIdentity(workspace, type, canonicalKey);
    }

    public static GraphEntityIdentity fromAuthority(GraphAuthorityReference authority,
                                                     GraphEntityType type) {
        if (authority == null || type == null) {
            throw new IllegalArgumentException("Graph authority and entity type are required");
        }
        if (type.authorityKind() != null && type.authorityKind() != authority.kind()) {
            throw new IllegalArgumentException("Graph authority kind does not match entity type");
        }
        return of(authority.workspace(), type, authority.kind().wireValue() + ":" + authority.stableId());
    }

    private static String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Graph canonical identity must not be blank");
        }
        return Normalizer.normalize(value, Normalizer.Form.NFC).trim();
    }
}
