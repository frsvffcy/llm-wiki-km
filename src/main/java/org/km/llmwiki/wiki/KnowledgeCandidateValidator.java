package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.KnowledgeCandidate;
import org.km.llmwiki.ai.AnalysisFailureCode;
import org.km.llmwiki.ai.LlmAnalysisValidationException;
import org.km.llmwiki.source.SourceChunk;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** Validates that candidate evidence belongs to the document that was analyzed. */
@Service
public class KnowledgeCandidateValidator {

    public List<KnowledgeCandidate> validate(long documentId, List<SourceChunk> sourceChunks,
                                             List<KnowledgeCandidate> candidates) {
        Set<Long> sourceChunkIds = sourceChunks.stream().map(SourceChunk::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (sourceChunkIds.size() != sourceChunks.size()
                || sourceChunks.stream().anyMatch(chunk -> chunk.documentId() != documentId)) {
            throw new IllegalArgumentException("sourceChunks must uniquely belong to the analyzed document");
        }
        for (KnowledgeCandidate candidate : candidates) {
            if (!sourceChunkIds.containsAll(candidate.evidenceSourceChunkIds())) {
                throw new LlmAnalysisValidationException(AnalysisFailureCode.ILLEGAL_EVIDENCE,
                        "Knowledge candidate cites a Source Chunk outside this document request");
            }
        }
        return List.copyOf(candidates);
    }
}
