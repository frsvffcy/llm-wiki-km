package org.km.llmwiki.graph;

import java.text.Normalizer;

/** Immutable provider-neutral Graph Entity projection model. */
public record GraphEntity(GraphEntityIdentity identity, String displayName,
                          GraphProvenance provenance, GraphMetadata metadata,
                          GraphProjectionVersion projectionVersion) {

    public static final int MAX_DISPLAY_NAME_CODE_POINTS = 256;

    public GraphEntity {
        if (identity == null || provenance == null || metadata == null || projectionVersion == null) {
            throw new IllegalArgumentException("Graph entity is incomplete");
        }
        if (!identity.workspace().equals(provenance.authority().workspace())) {
            throw new IllegalArgumentException("Graph entity provenance crosses workspace boundary");
        }
        if (identity.type().authorityKind() != null
                && identity.type().authorityKind() != provenance.authority().kind()) {
            throw new IllegalArgumentException("Graph entity authority kind does not match entity type");
        }
        if (identity.type().authorityKind() != null
                && !identity.equals(GraphEntityIdentity.fromAuthority(provenance.authority(), identity.type()))) {
            throw new IllegalArgumentException("Graph entity identity does not match provenance authority");
        }
        displayName = normalizeDisplayName(displayName);
    }

    public static GraphEntity of(GraphEntityIdentity identity, String displayName,
                                 GraphProvenance provenance, GraphMetadata metadata) {
        return new GraphEntity(identity, displayName, provenance, metadata,
                GraphProjectionVersion.initial());
    }

    private static String normalizeDisplayName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Graph entity display name must not be blank");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim();
        if (normalized.codePointCount(0, normalized.length()) > MAX_DISPLAY_NAME_CODE_POINTS) {
            throw new IllegalArgumentException("Graph entity display name is too long");
        }
        return normalized;
    }
}
