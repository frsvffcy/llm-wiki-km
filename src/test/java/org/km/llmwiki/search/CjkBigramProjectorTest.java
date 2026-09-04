package org.km.llmwiki.search;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class CjkBigramProjectorTest {

    @Test
    void emitsOverlappingHanBigramsAndKeepsSingleHanCharactersSearchable() {
        assertThat(CjkBigramProjector.tokens("全文搜尋"))
                .containsExactly("全文", "文搜", "搜尋");
        assertThat(CjkBigramProjector.tokens("甲"))
                .containsExactly("甲");
        assertThat(CjkBigramProjector.transform("全文搜尋"))
                .isEqualTo("全文 文搜 搜尋");
    }

    @Test
    void preservesTechnicalTokenBoundariesAndUsesRootLocaleCaseFolding() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(CjkBigramProjector.tokens("SpringBoot jOOQ SQLite FTS5 RAG LLM 2025"))
                    .containsExactly("springboot", "jooq", "sqlite", "fts5", "rag", "llm", "2025");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void normalizesNfcAndReturnsAnImmutableDeterministicTokenList() {
        String decomposed = "Cafe\u0301 全文";
        List<String> first = CjkBigramProjector.tokens(decomposed);
        List<String> second = CjkBigramProjector.tokens("Café 全文");

        assertThat(first).isEqualTo(second);
        assertThat(first).containsExactly("café", "全文");
        assertThatThrownBy(() -> first.add("mutation"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(CjkBigramProjector.transform(null)).isNull();
        assertThat(CjkBigramProjector.tokens(null)).isEmpty();
    }

    @Test
    void separatesHanFromLatinWithoutCorruptingMixedTechnicalInput() {
        assertThat(CjkBigramProjector.transform("系統SpringBoot與jOOQ全文搜尋"))
                .isEqualTo("系統 springboot 與 jooq 全文 文搜 搜尋");
        assertThat(CjkBigramProjector.tokens("SQLiteBackup FTS50"))
                .containsExactly("sqlitebackup", "fts50");
    }
}
