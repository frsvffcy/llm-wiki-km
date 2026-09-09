package org.km.llmwiki.source;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class SourceChunkerTest {

    private static final Pattern PARAGRAPH_BOUNDARY =
            Pattern.compile("(?:\\r\\n|\\r|\\n)[\\t ]*(?:\\r\\n|\\r|\\n)+");
    private static final Pattern MARKDOWN_HEADING =
            Pattern.compile("^[\\t ]{0,3}(#{1,6})[\\t ]+(.+?)[\\t ]*#*[\\t ]*$");

    @Test
    void chunksByHeadingsParagraphsAndPagesWhilePreservingContext() throws Exception {
        SourceChunker chunker = chunker();

        String content = """
                # Overview

                First paragraph.

                Second paragraph.

                ## Details

                Detail on the first page.\fDetail on the second page.
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
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.chunkPolicyVersion()).isEqualTo(SourceChunker.CHUNK_POLICY_VERSION));
    }

    @Test
    void keepsLongParagraphWholeInsteadOfHardSplittingItAtAFixedCharacterCount() throws Exception {
        SourceChunker chunker = chunker();
        String paragraph = "word ".repeat(SourceChunker.TARGET_MAX_CHUNK_LENGTH);

        List<SourceChunkDraft> chunks = chunker.chunk(paragraph, canonicalize(paragraph));

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.content()).hasSizeGreaterThan(SourceChunker.TARGET_MAX_CHUNK_LENGTH);
            assertThat(chunk.content()).startsWith("word word").endsWith("word");
            assertThat(chunk.normalizedContent()).hasSizeGreaterThan(SourceChunker.TARGET_MAX_CHUNK_LENGTH);
            assertThat(chunk.contentHash()).hasSize(64);
        });
    }

    @Test
    void blockDrivenPolicyStaysByteEquivalentToTheLegacyFlatTextAlgorithm() throws Exception {
        SourceChunker chunker = chunker();
        List<String> fixtures = List.of(
                """
                        # Overview

                        First paragraph.

                        Second paragraph.

                        ## Details

                        Detail on the first page.\fDetail on the second page.
                        """,
                "word ".repeat(SourceChunker.TARGET_MAX_CHUNK_LENGTH),
                """
                        ## Trailing hashes ##

                        Body under a decorated heading.

                        #NoSpaceIsNotAHeading

                        ### Level skip

                        Content after a level jump.\f   \t

                        Next page after whitespace-only blocks.

                        # Overview
                        """,
                """
                        # Root

                        ## Child

                        %s

                        Another paragraph after the oversized one.
                        """.formatted("filler ".repeat(900)),
                "\r\nFirst with CRLF.\r\n\r\n# CRLF Heading\r\n\r\nBody after CRLF heading.\f\f",
                "Single block only.");

        for (String content : fixtures) {
            List<SourceChunkDraft> legacy = legacyReferenceChunk(content, canonicalize(content));
            List<SourceChunkDraft> policy = chunker.chunk(
                    new ParsedDocument(content, Map.of()), canonicalize(content));

            assertThat(policy).as("fixture starting with %s", content.substring(0, Math.min(20, content.length())))
                    .containsExactlyElementsOf(legacy);
        }
    }

    @Test
    void reportsVersionOnePolicyIdentity() {
        assertThat(chunker().version()).isEqualTo("chunk-policy-v1-current");
    }

    @Test
    void providerMetadataOrderingDoesNotInfluenceChunkDeterminism() throws Exception {
        java.util.Map<String, String> firstOrdered = new java.util.LinkedHashMap<>();
        firstOrdered.put("title", "A");
        firstOrdered.put("author", "B");
        java.util.Map<String, String> secondOrdered = new java.util.LinkedHashMap<>();
        secondOrdered.put("author", "B");
        secondOrdered.put("title", "A");

        List<SourceChunkDraft> first = chunker().chunk(
                new ParsedDocument("Header\n\nBody content.", firstOrdered), canonicalize("Header\n\nBody content."));
        List<SourceChunkDraft> second = chunker().chunk(
                new ParsedDocument("Header\n\nBody content.", secondOrdered), canonicalize("Header\n\nBody content."));

        assertThat(first).containsExactlyElementsOf(second);
    }

    private static SourceChunker chunker() {
        return new SourceChunker(new ExtractedContentNormalizer(
                new ExtractedContentNormalizationProperties()));
    }

    private static ExtractedContentNormalizer.CanonicalNormalization canonicalize(String content) {
        return new ExtractedContentNormalizer(new ExtractedContentNormalizationProperties())
                .canonicalize(content);
    }

    /**
     * Independent copy of the original flat-text chunker algorithm (pre-refactor), used as
     * the reference the version 1 block-driven policy must stay byte-equivalent to.
     */
    private static List<SourceChunkDraft> legacyReferenceChunk(String content,
                                                               ExtractedContentNormalizer.CanonicalNormalization canonicalNormalization) {
        List<LegacyCandidate> candidates = new ArrayList<>();
        List<String> headingLevels = new ArrayList<>();
        String[] pages = content.split("\\f", -1);
        for (int pageIndex = 0; pageIndex < pages.length; pageIndex++) {
            LegacyAccumulator accumulator = null;
            for (String paragraph : PARAGRAPH_BOUNDARY.split(pages[pageIndex])) {
                String block = trimBoundaryWhitespace(paragraph);
                if (block.isEmpty()) {
                    continue;
                }
                Matcher heading = MARKDOWN_HEADING.matcher(block);
                if (heading.matches()) {
                    if (accumulator != null) {
                        candidates.add(toCandidate(accumulator, canonicalNormalization));
                    }
                    updateHeadingLevels(headingLevels, heading.group(1).length(), heading.group(2));
                    accumulator = new LegacyAccumulator(pageIndex + 1, currentSection(headingLevels),
                            currentHeadingPath(headingLevels));
                    accumulator.append(block);
                    continue;
                }

                if (accumulator == null) {
                    accumulator = new LegacyAccumulator(pageIndex + 1, currentSection(headingLevels),
                            currentHeadingPath(headingLevels));
                }
                if (!accumulator.isEmpty()
                        && accumulator.lengthWith(block) > SourceChunker.TARGET_MAX_CHUNK_LENGTH) {
                    candidates.add(toCandidate(accumulator, canonicalNormalization));
                    accumulator = new LegacyAccumulator(pageIndex + 1, currentSection(headingLevels),
                            currentHeadingPath(headingLevels));
                }
                accumulator.append(block);
            }
            if (accumulator != null && !accumulator.isEmpty()) {
                candidates.add(toCandidate(accumulator, canonicalNormalization));
            }
        }
        return retainOriginalEvidence(candidates);
    }

    private record LegacyCandidate(Integer pageNo, String section, String headingPath, String originalContent,
                                   String normalizedContent) {
    }

    private static LegacyCandidate toCandidate(LegacyAccumulator accumulator,
                                               ExtractedContentNormalizer.CanonicalNormalization canonicalNormalization) {
        String originalContent = accumulator.content();
        String normalizedContent = new ExtractedContentNormalizer(
                new ExtractedContentNormalizationProperties())
                .normalizeChunk(originalContent, canonicalNormalization);
        return new LegacyCandidate(accumulator.pageNo(), accumulator.section(), accumulator.headingPath(),
                originalContent, normalizedContent);
    }

    private static List<SourceChunkDraft> retainOriginalEvidence(List<LegacyCandidate> candidates) {
        List<SourceChunkDraft> chunks = new ArrayList<>();
        String pendingOriginalContent = null;
        for (LegacyCandidate candidate : candidates) {
            if (candidate.normalizedContent().isBlank()) {
                pendingOriginalContent = appendEvidence(pendingOriginalContent, candidate.originalContent());
                continue;
            }
            String originalContent = appendEvidence(pendingOriginalContent, candidate.originalContent());
            pendingOriginalContent = null;
            chunks.add(new SourceChunkDraft(chunks.size() + 1, candidate.pageNo(), candidate.section(),
                    candidate.headingPath(), originalContent, candidate.normalizedContent(),
                    sha256(candidate.normalizedContent()), SourceChunker.CHUNK_POLICY_VERSION));
        }
        if (pendingOriginalContent != null && !chunks.isEmpty()) {
            SourceChunkDraft previous = chunks.removeLast();
            chunks.add(new SourceChunkDraft(previous.chunkNo(), previous.pageNo(), previous.section(),
                    previous.headingPath(), appendEvidence(previous.content(), pendingOriginalContent),
                    previous.normalizedContent(), previous.contentHash(),
                    previous.chunkPolicyVersion()));
        }
        return List.copyOf(chunks);
    }

    private static String appendEvidence(String existing, String addition) {
        return existing == null ? addition : existing + "\n\n" + addition;
    }

    private static void updateHeadingLevels(List<String> headingLevels, int level, String heading) {
        while (headingLevels.size() >= level) {
            headingLevels.removeLast();
        }
        while (headingLevels.size() < level - 1) {
            headingLevels.add("");
        }
        headingLevels.add(heading.trim());
    }

    private static String currentSection(List<String> headingLevels) {
        for (int index = headingLevels.size() - 1; index >= 0; index--) {
            if (!headingLevels.get(index).isBlank()) {
                return headingLevels.get(index);
            }
        }
        return null;
    }

    private static String currentHeadingPath(List<String> headingLevels) {
        return headingLevels.stream().filter(heading -> !heading.isBlank())
                .reduce((parent, child) -> parent + " > " + child)
                .orElse(null);
    }

    private static String trimBoundaryWhitespace(String content) {
        return content.replaceFirst("^[\\s&&[^\\f]]+", "")
                .replaceFirst("[\\s&&[^\\f]]+$", "");
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class LegacyAccumulator {
        private final Integer pageNo;
        private final String section;
        private final String headingPath;
        private final StringBuilder content = new StringBuilder();

        private LegacyAccumulator(Integer pageNo, String section, String headingPath) {
            this.pageNo = pageNo;
            this.section = section;
            this.headingPath = headingPath;
        }

        private void append(String block) {
            if (!content.isEmpty()) {
                content.append("\n\n");
            }
            content.append(block);
        }

        private boolean isEmpty() {
            return content.isEmpty();
        }

        private int lengthWith(String block) {
            return content.length() + 2 + block.length();
        }

        private Integer pageNo() {
            return pageNo;
        }

        private String section() {
            return section;
        }

        private String headingPath() {
            return headingPath;
        }

        private String content() {
            return content.toString();
        }
    }
}
