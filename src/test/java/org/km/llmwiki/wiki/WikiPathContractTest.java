package org.km.llmwiki.wiki;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikiPathContractTest {

    private WikiPathContract contract;

    @BeforeEach
    void setUp() {
        contract = new WikiPathContract();
    }

    // =====================================================================
    // normalizeTitleToFileName
    // =====================================================================

    @Nested
    class NormalizeTitleToFileName {

        @ParameterizedTest(name = "''{0}'' -> ''{1}''")
        @CsvSource({
                "Spring Boot 3,                       spring-boot-3.md",
                "Spring Boot,                         spring-boot.md",
                "My Page,                             my-page.md",
                "  Leading and trailing spaces  ,     leading-and-trailing-spaces.md",
                "Multiple   Spaces   Between Words,   multiple-spaces-between-words.md",
                "Hello-World,                         hello-world.md",
                "CamelCase,                           camelcase.md",
                "數據庫 Database,                      數據庫-database.md",
                "知識管理系統,                          知識管理系統.md",
        })
        void normalizesNormalTitles(String title, String expectedFileName) {
            assertThat(contract.normalizeTitleToFileName(title)).isEqualTo(expectedFileName);
        }

        @Test
        void removesSpecialCharacters() {
            // Slashes, colons, angle brackets and other unsafe characters should be removed
            assertThat(contract.normalizeTitleToFileName("Hello/World")).isEqualTo("helloworld.md");
            assertThat(contract.normalizeTitleToFileName("Hello:World")).isEqualTo("helloworld.md");
            assertThat(contract.normalizeTitleToFileName("<script>")).isEqualTo("script.md");
            assertThat(contract.normalizeTitleToFileName("A & B")).isEqualTo("a-b.md");
        }

        @Test
        void collapsesConsecutiveHyphens() {
            assertThat(contract.normalizeTitleToFileName("A--B")).isEqualTo("a-b.md");
            assertThat(contract.normalizeTitleToFileName("A---B---C")).isEqualTo("a-b-c.md");
        }

        @Test
        void stripsLeadingAndTrailingHyphens() {
            assertThat(contract.normalizeTitleToFileName("-Hello-")).isEqualTo("hello.md");
            assertThat(contract.normalizeTitleToFileName("-Hello World-")).isEqualTo("hello-world.md");
        }

        @Test
        void truncatesVeryLongTitle() {
            String longTitle = "a".repeat(300);
            String result = contract.normalizeTitleToFileName(longTitle);
            // stem should be at most MAX_STEM_LENGTH chars + ".md"
            assertThat(result.length()).isLessThanOrEqualTo(WikiPathContract.MAX_STEM_LENGTH + ".md".length());
            assertThat(result).endsWith(".md");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void rejectsNullAndBlankTitles(String title) {
            assertThatThrownBy(() -> contract.normalizeTitleToFileName(title))
                    .isInstanceOf(WikiPathValidationException.class)
                    .satisfies(ex -> assertThat(((WikiPathValidationException) ex).reason())
                            .isEqualTo(WikiPathValidationException.Reason.INVALID_TITLE));
        }

        @Test
        void rejectsTitleWithOnlySpecialCharacters() {
            // After removing special chars, stem is empty
            assertThatThrownBy(() -> contract.normalizeTitleToFileName("///"))
                    .isInstanceOf(WikiPathValidationException.class)
                    .satisfies(ex -> assertThat(((WikiPathValidationException) ex).reason())
                            .isEqualTo(WikiPathValidationException.Reason.INVALID_TITLE));
        }
    }

    // =====================================================================
    // resolveLogicalPath
    // =====================================================================

    @Nested
    class ResolveLogicalPath {

        @ParameterizedTest(name = "{0} + ''{1}'' -> {2}")
        @CsvSource({
                "CONCEPT,       Spring Boot 3,       vault/concepts/spring-boot-3.md",
                "TECHNOLOGY,    Apache Kafka,         vault/technologies/apache-kafka.md",
                "DECISION,      Use SQLite,           vault/decisions/use-sqlite.md",
                "PERSON,        John Doe,             vault/people/john-doe.md",
                "ORGANIZATION,  GitHub,               vault/organizations/github.md",
                "HOWTO,         Install Java 21,      vault/howtos/install-java-21.md",
        })
        void buildsCorrectLogicalPath(WikiPageType type, String title, String expectedPath) {
            assertThat(contract.resolveLogicalPath(type, title)).isEqualTo(expectedPath);
        }

        @Test
        void rejectsNullPageType() {
            assertThatThrownBy(() -> contract.resolveLogicalPath(null, "Valid Title"))
                    .isInstanceOf(WikiPathValidationException.class)
                    .satisfies(ex -> assertThat(((WikiPathValidationException) ex).reason())
                            .isEqualTo(WikiPathValidationException.Reason.UNKNOWN_PAGE_TYPE));
        }

        @Test
        void propagatesTitleValidationException() {
            assertThatThrownBy(() -> contract.resolveLogicalPath(WikiPageType.CONCEPT, "///"))
                    .isInstanceOf(WikiPathValidationException.class)
                    .satisfies(ex -> assertThat(((WikiPathValidationException) ex).reason())
                            .isEqualTo(WikiPathValidationException.Reason.INVALID_TITLE));
        }
    }

    // =====================================================================
    // validateLogicalPath
    // =====================================================================

    @Nested
    class ValidateLogicalPath {

        @Test
        void acceptsValidPaths() {
            // Should complete without exception
            contract.validateLogicalPath("vault/concepts/spring-boot-3.md");
            contract.validateLogicalPath("vault/technologies/apache-kafka.md");
            contract.validateLogicalPath("vault/people/jane-smith.md");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void rejectsNullAndBlank(String path) {
            assertThatThrownBy(() -> contract.validateLogicalPath(path))
                    .isInstanceOf(WikiPathValidationException.class)
                    .satisfies(ex -> assertThat(((WikiPathValidationException) ex).reason())
                            .isEqualTo(WikiPathValidationException.Reason.INVALID_TITLE));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/vault/concepts/foo.md",
                "/etc/passwd",
                "/tmp/foo.md"
        })
        void rejectsAbsolutePaths(String path) {
            assertThatThrownBy(() -> contract.validateLogicalPath(path))
                    .isInstanceOf(WikiPathValidationException.class)
                    .satisfies(ex -> assertThat(((WikiPathValidationException) ex).reason())
                            .isEqualTo(WikiPathValidationException.Reason.ABSOLUTE_PATH_NOT_ALLOWED));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "vault/../secrets/key.txt",
                "vault/concepts/../../etc/passwd",
                "../vault/concepts/foo.md",
                "vault/concepts/../../../root.md"
        })
        void rejectsPathTraversal(String path) {
            assertThatThrownBy(() -> contract.validateLogicalPath(path))
                    .isInstanceOf(WikiPathValidationException.class)
                    .satisfies(ex -> assertThat(((WikiPathValidationException) ex).reason())
                            .isEqualTo(WikiPathValidationException.Reason.PATH_TRAVERSAL_DETECTED));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "inbox/foo.md",
                "archive/secret.pdf",
                "data/knowledge.db",
                "concepts/foo.md"
        })
        void rejectsPathsNotUnderVault(String path) {
            assertThatThrownBy(() -> contract.validateLogicalPath(path))
                    .isInstanceOf(WikiPathValidationException.class)
                    .satisfies(ex -> assertThat(((WikiPathValidationException) ex).reason())
                            .isEqualTo(WikiPathValidationException.Reason.OUTSIDE_VAULT_BOUNDARY));
        }
    }

    // =====================================================================
    // resolveAndValidateRealPath — tested without a live workspace in
    // WikiVaultBoundaryIntegrationTest; here we test failure paths only.
    // =====================================================================

    @Nested
    class ResolveAndValidateRealPath {

        @Test
        void rejectsNonExistentParentDirectory() {
            // A vault root that doesn't exist means toRealPath() will fail
            Path nonExistentVault = Path.of("/tmp/definitely-does-not-exist-" + System.nanoTime() + "/vault");
            assertThatThrownBy(() ->
                    contract.resolveAndValidateRealPath(nonExistentVault, "vault/concepts/test.md"))
                    .isInstanceOf(WikiPathValidationException.class)
                    .satisfies(ex -> assertThat(((WikiPathValidationException) ex).reason())
                            .isEqualTo(WikiPathValidationException.Reason.OUTSIDE_VAULT_BOUNDARY));
        }
    }
}
