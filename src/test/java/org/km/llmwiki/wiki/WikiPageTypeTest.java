package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikiPageTypeTest {

    // -----------------------------------------------------------------------
    // folderName contract
    // -----------------------------------------------------------------------

    @Test
    void everyTypeHasANonBlankFolderName() {
        for (WikiPageType type : WikiPageType.values()) {
            assertThat(type.folderName())
                    .as("folderName() for %s", type)
                    .isNotBlank()
                    .doesNotContain("/")
                    .doesNotContain("..");
        }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "CONCEPT,        concepts",
            "TECHNOLOGY,     technologies",
            "TROUBLESHOOTING,troubleshooting",
            "DECISION,       decisions",
            "PROJECT,        projects",
            "REFERENCE,      references",
            "HOWTO,          howtos",
            "PERSON,         people",
            "ORGANIZATION,   organizations"
    })
    void folderNameMatchesExpected(WikiPageType type, String expectedFolder) {
        assertThat(type.folderName()).isEqualTo(expectedFolder);
    }

    // -----------------------------------------------------------------------
    // from() – happy path
    // -----------------------------------------------------------------------

    @Test
    void fromAcceptsExactUpperCase() {
        assertThat(WikiPageType.from("CONCEPT")).isEqualTo(WikiPageType.CONCEPT);
        assertThat(WikiPageType.from("TECHNOLOGY")).isEqualTo(WikiPageType.TECHNOLOGY);
        assertThat(WikiPageType.from("ORGANIZATION")).isEqualTo(WikiPageType.ORGANIZATION);
    }

    @Test
    void fromIsCaseInsensitive() {
        assertThat(WikiPageType.from("concept")).isEqualTo(WikiPageType.CONCEPT);
        assertThat(WikiPageType.from("Concept")).isEqualTo(WikiPageType.CONCEPT);
        assertThat(WikiPageType.from("HOWTO")).isEqualTo(WikiPageType.HOWTO);
        assertThat(WikiPageType.from("howto")).isEqualTo(WikiPageType.HOWTO);
    }

    @Test
    void fromTrimsWhitespaceBeforeParsing() {
        assertThat(WikiPageType.from("  DECISION  ")).isEqualTo(WikiPageType.DECISION);
        assertThat(WikiPageType.from(" concept ")).isEqualTo(WikiPageType.CONCEPT);
    }

    // -----------------------------------------------------------------------
    // from() – rejection
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void fromRejectsNullAndBlank(String input) {
        assertThatThrownBy(() -> WikiPageType.from(input))
                .isInstanceOf(WikiPathValidationException.class)
                .satisfies(ex -> assertThat(((WikiPathValidationException) ex).reason())
                        .isEqualTo(WikiPathValidationException.Reason.UNKNOWN_PAGE_TYPE));
    }

    // -----------------------------------------------------------------------
    // fromFolderName()
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "concepts,        CONCEPT",
            "technologies,    TECHNOLOGY",
            "troubleshooting, TROUBLESHOOTING",
            "decisions,       DECISION",
            "projects,        PROJECT",
            "references,      REFERENCE",
            "howtos,          HOWTO",
            "people,          PERSON",
            "organizations,   ORGANIZATION"
    })
    void fromFolderNameMatchesExpected(String folder, WikiPageType expectedType) {
        assertThat(WikiPageType.fromFolderName(folder)).isEqualTo(expectedType);
        assertThat(WikiPageType.fromFolderName(folder.toUpperCase())).isEqualTo(expectedType);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "unknown", "vault", "CONCEPT"})
    void fromFolderNameRejectsInvalid(String folder) {
        assertThatThrownBy(() -> WikiPageType.fromFolderName(folder))
                .isInstanceOf(WikiPathValidationException.class)
                .satisfies(ex -> assertThat(((WikiPathValidationException) ex).reason())
                        .isEqualTo(WikiPathValidationException.Reason.UNKNOWN_PAGE_TYPE));
    }
}
