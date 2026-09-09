package org.km.llmwiki.source;

import java.util.Objects;

public record SourceChunkDraft(int chunkNo, Integer pageNo, String section, String headingPath,
                               String content, String normalizedContent, String contentHash,
                               String chunkPolicyVersion) {

    public SourceChunkDraft {
        chunkPolicyVersion = Objects.requireNonNull(chunkPolicyVersion, "chunkPolicyVersion must not be null");
    }
}
