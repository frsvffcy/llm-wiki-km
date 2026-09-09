package org.km.llmwiki.source;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

@Tag("unit")
class HeadingAnchoredChunkingPolicyTest {

    private final ExtractedContentNormalizer normalizer =
            new ExtractedContentNormalizer(new ExtractedContentNormalizationProperties());

    @Test
    void reportsVersionTwoPolicyIdentity() {
        assertThat(policy().version()).isEqualTo("chunk-policy-v2-heading-anchor");
    }

    @Test
    void bindsHeadingToItsFirstFollowingParagraphInsteadOfSplittingOnCharacterCount() {
        String heading = "# Overview";
        String firstParagraph = "first ".repeat(1_200);
        String secondParagraph = "second ".repeat(1_200);
        ParsedDocument parsed = parsed(
                block(1, ParsedBlockKind.HEADING, 1, 1, heading, "Overview"),
                block(2, ParsedBlockKind.PARAGRAPH, 0, 1, firstParagraph.strip(), null),
                block(3, ParsedBlockKind.PARAGRAPH, 0, 1, secondParagraph.strip(), null));

        List<SourceChunkDraft> chunks = policy().chunk(parsed, normalize(parsed));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).startsWith("# Overview").contains("first");
        assertThat(chunks.get(0).content()).hasSizeGreaterThan(SourceChunker.TARGET_MAX_CHUNK_LENGTH);
        assertThat(chunks.get(1).content()).doesNotContain("first").contains("second");
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.headingPath()).isEqualTo("Overview"));
    }

    @Test
    void emitsTableFigureAndCaptionBlocksAsStandaloneAtomicChunks() {
        String table = "| a | b |\n|---|---|\n| 1 | 2 |";
        ParsedDocument parsed = parsed(
                block(1, ParsedBlockKind.PARAGRAPH, 0, 1, "before table", null),
                block(2, ParsedBlockKind.TABLE, 0, 1, table, null),
                block(3, ParsedBlockKind.PARAGRAPH, 0, 1, "after table", null),
                block(4, ParsedBlockKind.FIGURE, 0, 1, "figure description", null),
                block(5, ParsedBlockKind.CAPTION, 0, 2, "caption on page two", null));

        List<SourceChunkDraft> chunks = policy().chunk(parsed, normalize(parsed));

        assertThat(chunks).extracting(SourceChunkDraft::content).containsExactly(
                "before table", table, "after table", "figure description", "caption on page two");
        assertThat(chunks).extracting(SourceChunkDraft::chunkNo).containsExactly(1, 2, 3, 4, 5);
        assertThat(chunks.get(4).pageNo()).isEqualTo(2);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.chunkPolicyVersion())
                .isEqualTo(HeadingAnchoredChunkingPolicy.CHUNK_POLICY_VERSION));
    }

    @Test
    void keepsHeadingContextCarriedAcrossAtomicChunksAndPageFlushes() {
        ParsedDocument parsed = parsed(
                block(1, ParsedBlockKind.HEADING, 1, 1, "# Section", "Section"),
                block(2, ParsedBlockKind.PARAGRAPH, 0, 1, "bound paragraph", null),
                block(3, ParsedBlockKind.TABLE, 0, 1, "| x |", null),
                block(4, ParsedBlockKind.PARAGRAPH, 0, 2, "page two paragraph", null));

        List<SourceChunkDraft> chunks = policy().chunk(parsed, normalize(parsed));

        assertThat(chunks).extracting(SourceChunkDraft::section, SourceChunkDraft::headingPath)
                .containsExactly(
                        tuple("Section", "Section"),
                        tuple("Section", "Section"),
                        tuple("Section", "Section"));
        assertThat(chunks.get(2).pageNo()).isEqualTo(2);
    }

    @Test
    void neverSplitsAnIndividualOversizedBlock() {
        String oversized = "word ".repeat(SourceChunker.TARGET_MAX_CHUNK_LENGTH).strip();
        ParsedDocument parsed = parsed(
                block(1, ParsedBlockKind.TABLE, 0, 1, oversized, null));

        List<SourceChunkDraft> chunks = policy().chunk(parsed, normalize(parsed));

        assertThat(chunks).singleElement().satisfies(chunk ->
                assertThat(chunk.content()).isEqualTo(oversized.strip()));
    }

    @Test
    void treatsConsecutiveNullPageBlocksAsOnePageInsteadOfPerBlockFlushes() {
        ParsedDocument parsed = parsed(
                block(1, ParsedBlockKind.PARAGRAPH, 0, null, "weak parser block one", null),
                block(2, ParsedBlockKind.PARAGRAPH, 0, null, "weak parser block two", null));

        List<SourceChunkDraft> chunks = policy().chunk(parsed, normalize(parsed));

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.pageNo()).isNull();
            assertThat(chunk.content()).contains("weak parser block one", "weak parser block two");
        });
    }

    @Test
    void producesDeterministicOutputAcrossRepeatedRuns() {
        List<ParsedBlock> blocks = List.of(
                block(1, ParsedBlockKind.HEADING, 1, 1, "# Root", "Root"),
                block(2, ParsedBlockKind.PARAGRAPH, 0, 1, "alpha", null),
                block(3, ParsedBlockKind.TABLE, 0, 1, "| t |", null),
                block(4, ParsedBlockKind.PARAGRAPH, 0, 1, "beta", null));
        ParsedDocument parsed = new ParsedDocument("irrelevant", Map.of(), blocks, "test", "v1");

        List<SourceChunkDraft> first = policy().chunk(parsed, normalize(parsed));
        List<SourceChunkDraft> second = policy().chunk(parsed, normalize(parsed));

        assertThat(first).containsExactlyElementsOf(second);
    }

    private HeadingAnchoredChunkingPolicy policy() {
        return new HeadingAnchoredChunkingPolicy(normalizer);
    }

    private ExtractedContentNormalizer.CanonicalNormalization normalize(ParsedDocument parsed) {
        return normalizer.canonicalize(parsed.content());
    }

    private ParsedDocument parsed(ParsedBlock... blocks) {
        return new ParsedDocument("irrelevant", Map.of(), List.of(blocks), "test", "v1");
    }

    private ParsedBlock block(int ordinal, ParsedBlockKind kind, int headingLevel, Integer pageNo,
                              String text, String headingTitle) {
        return new ParsedBlock(ordinal, kind, headingLevel, pageNo, text, headingTitle, null);
    }
}
