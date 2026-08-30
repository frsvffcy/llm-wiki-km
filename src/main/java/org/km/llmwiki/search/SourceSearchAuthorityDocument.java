package org.km.llmwiki.search;

import java.util.List;

/** Workspace-scoped canonical document state used to derive the rebuildable Source FTS projection. */
public record SourceSearchAuthorityDocument(long workspaceId, long documentId, String documentName,
                                            String documentStatus, String parseStatus,
                                            List<SourceSearchAuthorityChunk> chunks) {
    public SourceSearchAuthorityDocument {
        chunks = List.copyOf(chunks);
    }
}
