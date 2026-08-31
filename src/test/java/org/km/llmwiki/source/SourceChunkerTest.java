package org.km.llmwiki.source;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class SourceChunkerTest {

    @Test
    void chunksByHeadingsParagraphsAndPagesWhilePreservingContext() {
        SourceChunker chunker = new SourceChunker(new ExtractedContentNormalizer(
                new ExtractedContentNormalizationProperties()));

        String content = """
                # Overview

                First paragraph.

                Second paragraph.

                ## Details

                Detail on the first page.Detail on the second page.
                """;
        List<SourceChunkDraft> chunks = chunker.chunk(content, canonicalize(content));

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0))
                .extracting(SourceChunkDraft::chunkNo, SourceChunkDraft::pageNo,
                        SourceChunkDraft::section, SourceChunkDraft::headingPath)
                .containsExactly(1, 1, "Overview", "Overview");
        assertThat(chunks.get(0).content()).contains("First paragraph.", "Second paragraph.");
        assertThat(chunks.get(1))
                .extracting(SourceChunkDraft::chunkNo, SourceChunkDraft::pageNo,
                        SourceChunkDraft::section, SourceChunkDraft::headingPath)
                .containsExactly(2, 1, "Details", "Overview > Details");
        assertThat(chunks.get(2))
                .extracting(SourceChunkDraft::chunkNo, SourceChunkDraft::pageNo,
                        SourceChunkDraft::section, SourceChunkDraft::headingPath)
                .containsExactly(3, 2, "Details", "Overview > Details");
    }

    @Test
    void keepsLongParagraphWholeInsteadOfHardSplittingItAtAFixedCharacterCount() {
        SourceChunker chunker = new SourceChunker(new ExtractedContentNormalizer(
                new ExtractedContentNormalizationProperties()));
        String paragraph = "word ".repeat(SourceChunker.TARGET_MAX_CHUNK_LENGTH);

        List<SourceChunkDraft> chunks = chunker.chunk(paragraph, canonicalize(paragraph));

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.content()).hasSizeGreaterThan(SourceChunker.TARGET_MAX_CHUNK_LENGTH);
            assertThat(chunk.content()).startsWith("word word").endsWith("word");
            assertThat(chunk.normalizedContent()).hasSizeGreaterThan(SourceChunker.TARGET_MAX_CHUNK_LENGTH);
            assertThat(chunk.contentHash()).hasSize(64);
        });
    }

    private static ExtractedContentNormalizer.CanonicalNormalization canonicalize(String content) {
        return new ExtractedContentNormalizer(new ExtractedContentNormalizationProperties()).canonicalize(content);
    }
}
