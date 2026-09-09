package org.km.llmwiki.source;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@Tag("unit")
class ParsedDocumentTest {

    @Test
    void canonicalConstructorRequiresParserProvenanceAndOrderedBlockOrdinals() {
        ParsedDocument parsed = new ParsedDocument("內容", Map.of("title", "設計"),
                List.of(new ParsedBlock(1, ParsedBlockKind.PARAGRAPH, 0, 1, "內容", null, null)),
                "parser", "v1");

        assertThat(parsed.parserId()).isEqualTo("parser");
        assertThat(parsed.parserVersion()).isEqualTo("v1");
        assertThat(parsed.blocks()).hasSize(1);
        assertThat(parsed.metadata()).isUnmodifiable();
        assertThat(parsed.blocks()).isUnmodifiable();

        assertThatIllegalArgumentException().isThrownBy(() -> new ParsedDocument("內容", Map.of(),
                List.of(new ParsedBlock(1, ParsedBlockKind.PARAGRAPH, 0, 1, "a", null, null)),
                " ", "v1"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ParsedDocument("內容", Map.of(),
                List.of(new ParsedBlock(1, ParsedBlockKind.PARAGRAPH, 0, 1, "a", null, null)),
                "parser", " "));
        assertThatIllegalArgumentException().isThrownBy(() -> new ParsedDocument("內容", Map.of(),
                List.of(new ParsedBlock(2, ParsedBlockKind.PARAGRAPH, 0, 1, "a", null, null)),
                "parser", "v1"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ParsedDocument("內容", Map.of(),
                List.of(new ParsedBlock(1, ParsedBlockKind.PARAGRAPH, 0, 1, "a", null, null),
                        new ParsedBlock(3, ParsedBlockKind.PARAGRAPH, 0, 1, "b", null, null)),
                "parser", "v1"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ParsedDocument("內容", Map.of(),
                List.of(new ParsedBlock(2, ParsedBlockKind.PARAGRAPH, 0, 1, "a", null, null),
                        new ParsedBlock(1, ParsedBlockKind.PARAGRAPH, 0, 1, "b", null, null)),
                "parser", "v1"));
    }

    @Test
    void compatibilityConstructorSegmentsFlatTextWithLegacyProvenance() throws Exception {
        ParsedDocument parsed = new ParsedDocument("# Title\n\nBody.", Map.of());

        assertThat(parsed.parserId()).isEqualTo("flat-legacy");
        assertThat(parsed.parserVersion()).isEqualTo("v0");
        assertThat(parsed.blocks()).extracting(ParsedBlock::kind, ParsedBlock::headingTitle).containsExactly(
                tuple(ParsedBlockKind.HEADING, "Title"),
                tuple(ParsedBlockKind.PARAGRAPH, null));
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
