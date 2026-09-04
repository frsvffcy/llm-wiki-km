package org.km.llmwiki.testsupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@Tag("unit")
class TestTierCoverageGuardTest {

    @Test
    void actualCompiledInventoryHasNoUnclassifiedExecutableTests() {
        assertThatCode(TestTierCoverageGuard::assertComplete).doesNotThrowAnyException();
        assertThat(TestTierCoverageGuard.scan())
                .extracting(TestTierCoverageGuard.TestClass::className)
                .doesNotContain("org.km.llmwiki.testsupport.IsolatedIntegrationTest");
    }

    @Test
    void resolvesComposedAndInheritedIntegrationTag() {
        assertThat(TestTierCoverageGuard.tiersOf(IsolatedIntegrationTest.class))
                .contains("integration");
        assertThat(TestTierCoverageGuard.tiersOf(load("org.km.llmwiki.LlmWikiKmApplicationTests")))
                .contains("integration");
    }

    @Test
    void resolvesEnclosingTagForNestedExecutableTests() {
        Class<?> nested = load("org.km.llmwiki.wiki.WikiPathContractTest$NormalizeTitleToFileName");

        assertThat(TestTierCoverageGuard.isExecutableTestClass(nested)).isTrue();
        assertThat(TestTierCoverageGuard.tiersOf(nested)).contains("contract");
    }

    @Test
    void excludesAbstractAndNonTestSupportClasses() {
        assertThat(TestTierCoverageGuard.isExecutableTestClass(IsolatedIntegrationTest.class)).isFalse();
        assertThat(TestTierCoverageGuard.isExecutableTestClass(ContextCacheMetricsListener.class)).isFalse();
    }

    @Test
    void recognizesTheThreeSupportedTiersOnlyAsCoverage() {
        assertThat(TestTierCoverageGuard.REQUIRED_TIERS).isEqualTo(Set.of("unit", "contract", "integration"));
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, TestTierCoverageGuardTest.class.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new AssertionError(exception);
        }
    }
}
