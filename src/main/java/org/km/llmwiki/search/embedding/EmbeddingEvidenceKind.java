package org.km.llmwiki.search.embedding;

/** Canonical evidence families that can have an embedding projection. */
public enum EmbeddingEvidenceKind {
    WIKI("WIKI"),
    SOURCE_CHUNK("SOURCE");

    private final String storageValue;

    EmbeddingEvidenceKind(String storageValue) {
        this.storageValue = storageValue;
    }

    /** Stable readiness corpus key; SOURCE_CHUNK is stored as SOURCE for migration/API compatibility. */
    public String storageValue() {
        return storageValue;
    }

    public static EmbeddingEvidenceKind fromStorageValue(String value) {
        for (EmbeddingEvidenceKind kind : values()) {
            if (kind.storageValue.equals(value)) return kind;
        }
        throw new IllegalArgumentException("Unknown embedding corpus: " + value);
    }
}
