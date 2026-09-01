package org.km.llmwiki.search;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("contract")
class SearchSnippetTest {

    @Test
    void highlightsTechnicalTermsBesideHanButRejectsLatinSubstrings() {
        String snippet = SearchSnippet.canonical(
                "系統SpringBoot與 SQLiteBackup、FTS50；SQLite FTS5",
                "SpringBoot SQLite FTS5");

        assertThat(snippet).contains("系統<mark>SpringBoot</mark>與");
        assertThat(snippet).contains("SQLiteBackup、FTS50；<mark>SQLite</mark> <mark>FTS5</mark>");
        assertThat(snippet).doesNotContain("SQLite<mark>Backup</mark>")
                .doesNotContain("FTS<mark>50</mark>");
    }

    @Test
    void escapesCanonicalMarkupAndHighlightsTheVisibleTechnicalText() {
        String snippet = SearchSnippet.canonical("A & <tag> \"quoted\" 'text'", "tag");

        assertThat(snippet).isEqualTo("A &amp; &lt;<mark>tag</mark>&gt; &quot;quoted&quot; &#39;text&#39;");
    }

    @Test
    void keepsEmojiAsWholeCodePointsWhenBoundingAndRetainsHighlight() {
        String snippet = SearchSnippet.canonical("😀".repeat(400) + "全文搜尋" + "😀".repeat(400), "全文搜尋");

        String visible = snippet.replace("<mark>", "").replace("</mark>", "");
        assertThat(snippet).contains("<mark>全文搜尋</mark>")
                .doesNotContain("\uFFFD");
        assertThat(visible.codePointCount(0, visible.length())).isLessThanOrEqualTo(280);
    }
}
