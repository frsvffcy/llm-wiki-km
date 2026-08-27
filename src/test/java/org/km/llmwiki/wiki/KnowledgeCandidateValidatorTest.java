package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.KnowledgeCandidate;
import org.km.llmwiki.ai.KnowledgeCandidateType;
import org.km.llmwiki.ai.LlmAnalysisValidationException;
import org.km.llmwiki.source.SourceChunk;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeCandidateValidatorTest {

    private final KnowledgeCandidateValidator validator = new KnowledgeCandidateValidator();

    @Test
    void acceptsZeroOrMultipleCandidatesWithTraceableEvidenceFromTheSameDocument() {
        List<SourceChunk> chunks = List.of(chunk(41, 7, "原始內容一", "正規化內容一"),
                chunk(42, 7, "原始內容二", "正規化內容二"));
        List<KnowledgeCandidate> candidates = List.of(candidate("概念", List.of(41L)),
                candidate("流程", List.of(41L, 42L)));

        assertThat(validator.validate(7, chunks, candidates)).containsExactlyElementsOf(candidates);
        assertThat(validator.validate(7, chunks, List.of())).isEmpty();
        assertThat(chunks).extracting(SourceChunk::content, SourceChunk::normalizedContent)
                .containsExactly(tuple("原始內容一", "正規化內容一"), tuple("原始內容二", "正規化內容二"));
    }

    @Test
    void rejectsUnknownOrCrossDocumentEvidenceAndDuplicateEvidenceIds() {
        List<SourceChunk> chunks = List.of(chunk(41, 7, "原始內容", "正規化內容"));

        assertThatThrownBy(() -> validator.validate(7, chunks, List.of(candidate("無效", List.of(99L)))))
                .isInstanceOf(LlmAnalysisValidationException.class)
                .hasMessageContaining("outside this document request");
        assertThatThrownBy(() -> validator.validate(7, List.of(chunk(41, 8, "原始內容", "正規化內容")),
                List.of(candidate("跨文件", List.of(41L)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("analyzed document");
        assertThatThrownBy(() -> candidate("重複", List.of(41L, 41L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain duplicates");
    }

    private static KnowledgeCandidate candidate(String title, List<Long> sourceChunkIds) {
        return new KnowledgeCandidate(title, KnowledgeCandidateType.CONCEPT, "候選摘要", sourceChunkIds, 0.7, "測試理由");
    }

    private static SourceChunk chunk(long id, long documentId, String content, String normalizedContent) {
        return new SourceChunk(id, documentId, 1, null, null, null, content, normalizedContent, "hash");
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
