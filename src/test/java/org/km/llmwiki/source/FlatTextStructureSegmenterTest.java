package org.km.llmwiki.source;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class FlatTextStructureSegmenterTest {

    @Test
    void assignsAscendingOrdinalsAndParseOrderPagesWithoutFabricatingRichKinds() throws Exception {
        String content = """
                Intro paragraph.

                # Heading One

                Body under heading one.

                ## Heading Two ##

                Body under heading two.
                \fBody on page two.
                """;

        List<ParsedBlock> blocks = FlatTextStructureSegmenter.segment(content, 100);

        assertThat(blocks).extracting(ParsedBlock::stableOrdinal).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(blocks).extracting(ParsedBlock::kind).containsExactly(
                ParsedBlockKind.PARAGRAPH,
                ParsedBlockKind.HEADING,
                ParsedBlockKind.PARAGRAPH,
                ParsedBlockKind.HEADING,
                ParsedBlockKind.PARAGRAPH,
                ParsedBlockKind.PARAGRAPH);
        assertThat(blocks).extracting(ParsedBlock::pageNo).containsExactly(1, 1, 1, 1, 1, 2);
        assertThat(blocks).extracting(ParsedBlock::headingLevel).containsExactly(0, 1, 0, 2, 0, 0);
        assertThat(blocks).extracting(ParsedBlock::headingTitle)
                .containsExactly(null, "Heading One", null, "Heading Two", null, null);
        assertThat(blocks).extracting(ParsedBlock::text).containsExactly(
                "Intro paragraph.",
                "# Heading One",
                "Body under heading one.",
                "## Heading Two ##",
                "Body under heading two.",
                "Body on page two.");
        assertThat(blocks).allSatisfy(block -> assertThat(block.boundingBox()).isNull());
        assertThat(blocks).extracting(ParsedBlock::kind)
                .doesNotContain(ParsedBlockKind.TABLE, ParsedBlockKind.FIGURE, ParsedBlockKind.CAPTION);
    }

    @Test
    void trimsBlockBoundariesAndSkipsWhitespaceOnlyBlocks() throws Exception {
        String content = "\n\n  \t leading whitespace\n\nmiddle\n\ntrailing whitespace \t\n\n   \n\n\t\f\n\t page two \n";

        List<ParsedBlock> blocks = FlatTextStructureSegmenter.segment(content, 10);

        assertThat(blocks).extracting(ParsedBlock::text).containsExactly(
                "leading whitespace", "middle", "trailing whitespace", "page two");
        assertThat(blocks).extracting(ParsedBlock::pageNo).containsExactly(1, 1, 1, 2);
        assertThat(blocks).extracting(ParsedBlock::stableOrdinal).containsExactly(1, 2, 3, 4);
    }

    @Test
    void rejectsHeadingShapedTextWithoutMarkdownHeadingSyntax() throws Exception {
        List<ParsedBlock> blocks = FlatTextStructureSegmenter.segment("#nospace\nFour#NoSpace", 10);

        assertThat(blocks).allSatisfy(block -> assertThat(block.kind()).isEqualTo(ParsedBlockKind.PARAGRAPH));
    }

    @Test
    void throwsTypedResourceLimitWhenBlocksExceedTheCap() throws Exception {
        String content = String.join("\n\n", java.util.Collections.nCopies(6, "block"));

        List<ParsedBlock> blocks = FlatTextStructureSegmenter.segment(content, 6);
        assertThat(blocks).hasSize(6);

        assertThatThrownBy(() -> FlatTextStructureSegmenter.segment(content, 5))
                .isInstanceOf(DocumentParserResourceLimitException.class)
                .satisfies(exception -> assertThat(
                        ((DocumentParserResourceLimitException) exception).resource())
                        .isEqualTo(DocumentParserResourceLimitException.Resource.STRUCTURE_BLOCKS));
    }

    @Test
    void emptyContentYieldsNoBlocks() throws Exception {
        assertThat(FlatTextStructureSegmenter.segment("", 10)).isEmpty();
        assertThat(FlatTextStructureSegmenter.segment("\n\n  \n\f \n", 10)).isEmpty();
    }
}
