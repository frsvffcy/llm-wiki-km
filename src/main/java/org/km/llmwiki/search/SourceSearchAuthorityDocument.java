package org.km.llmwiki.search;

import java.util.List;

/** Workspace-scoped canonical document state used to derive the rebuildable Source FTS projection. */
public record SourceSearchAuthorityDocument(long workspaceId, long documentId, String documentName,
                                            String documentSha256, String documentStatus,
                                            String parseStatus,
                                            List<SourceSearchAuthorityChunk> chunks) {
    public SourceSearchAuthorityDocument {
        if (documentSha256 == null || documentSha256.isBlank()) {
            throw new IllegalArgumentException("Source document authority requires the archive sha256");
        }
        chunks = List.copyOf(chunks);
    }
}
