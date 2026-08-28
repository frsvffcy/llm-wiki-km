package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikiPageTest {

    @Test
    void createsMinimaValidPage() {
        WikiPage page = new WikiPage(
                "Spring Boot 3",
                WikiPageType.TECHNOLOGY,
                "vault/technologies/spring-boot-3.md",
                null,
                null,
                null,
                null);

        assertThat(page.title()).isEqualTo("Spring Boot 3");
        assertThat(page.pageType()).isEqualTo(WikiPageType.TECHNOLOGY);
        assertThat(page.logicalRelativePath()).isEqualTo("vault/technologies/spring-boot-3.md");
        assertThat(page.summary()).isNull();
        assertThat(page.tags()).isEmpty();
        assertThat(page.aliases()).isEmpty();
        assertThat(page.sourceDocumentIds()).isEmpty();
    }

    @Test
    void collectionsAreUnmodifiable() {
        WikiPage page = new WikiPage(
                "Test",
                WikiPageType.CONCEPT,
                "vault/concepts/test.md",
                "A test concept",
                List.of("java", "testing"),
                List.of("Test Concept"),
                List.of(1L, 2L));

        assertThatThrownBy(() -> page.tags().add("extra"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> page.aliases().add("extra"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> page.sourceDocumentIds().add(99L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullOrBlankTitle() {
        assertThatThrownBy(() -> new WikiPage(null, WikiPageType.CONCEPT, "vault/concepts/foo.md", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WikiPage("  ", WikiPageType.CONCEPT, "vault/concepts/foo.md", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullPageType() {
        assertThatThrownBy(() -> new WikiPage("Title", null, "vault/concepts/foo.md", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullOrBlankLogicalRelativePath() {
        assertThatThrownBy(() -> new WikiPage("Title", WikiPageType.CONCEPT, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WikiPage("Title", WikiPageType.CONCEPT, "  ", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMismatchedPageTypeAndPathFolder() {
        // Path is in technologies/ but pageType is CONCEPT
        assertThatThrownBy(() -> new WikiPage(
                "Title",
                WikiPageType.CONCEPT,
                "vault/technologies/spring-boot-3.md",
                null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match logicalRelativePath folder");
    }

    @Test
    void rejectsUncontrolledPathInConstructor() {
        assertThatThrownBy(() -> new WikiPage(
                "Title",
                WikiPageType.CONCEPT,
                "vault/random-folder/test.md",
                null, null, null, null))
                .isInstanceOf(WikiPathValidationException.class);
    }

    @Test
    void factoryMethodCreatesValidPageWithCanonicalPath() {
        WikiPage page = WikiPage.create(
                "Spring Boot 3",
                WikiPageType.TECHNOLOGY,
                "Spring framework runtime",
                List.of("java", "framework"),
                List.of("Boot 3"),
                List.of(10L, 20L));

        assertThat(page.title()).isEqualTo("Spring Boot 3");
        assertThat(page.pageType()).isEqualTo(WikiPageType.TECHNOLOGY);
        assertThat(page.logicalRelativePath()).isEqualTo("vault/technologies/spring-boot-3.md");
        assertThat(page.summary()).isEqualTo("Spring framework runtime");
        assertThat(page.tags()).containsExactly("java", "framework");
        assertThat(page.aliases()).containsExactly("Boot 3");
        assertThat(page.sourceDocumentIds()).containsExactly(10L, 20L);
    }
}
