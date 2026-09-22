package org.km.llmwiki.search;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("contract")
class ScopedDocumentQueryFallbackPolicyTest {

    private final ScopedDocumentQueryFallbackPolicy policy =
            new ScopedDocumentQueryFallbackPolicy();

    @Test
    void preservesExactTechnicalAnchorsAndDropsUnmatchedFiller() {
        assertThat(policy.fallback("busy_timeout 這個設定要怎麼調整"))
                .contains("busy_timeout");
        assertThat(policy.fallback("busy_timeout unicorn"))
                .contains("busy_timeout");
        assertThat(policy.fallback("ORA-12899 發生時要怎麼調整"))
                .contains("ORA-12899");
        assertThat(policy.fallback("NoSuchMethodError 怎麼處理"))
                .contains("NoSuchMethodError");
    }

    @Test
    void reducesOnlyBoundedConversationalAffixesForNaturalCjkQuestions() {
        assertThat(policy.fallback("請問知識管理如何運作？"))
                .contains("知識管理");
        assertThat(policy.fallback("知識管理要怎麼設定"))
                .contains("知識管理");
    }

    @Test
    void doesNotTurnArbitraryTermsIntoBroadOrSemantics() {
        assertThat(policy.fallback("alpha unicorn")).isEmpty();
        assertThat(policy.fallback("完全無關的自然語句")).isEmpty();
        assertThat(policy.fallback("busy_timeout")).isEmpty();
        assertThat(policy.fallback("請問是什麼？")).isEmpty();
    }

    @Test
    void isDeterministicAndEmitsAtMostOneFallbackQuery() {
        assertThat(policy.fallback("pkg.name busy_timeout 怎麼設定"))
                .contains("pkg.name busy_timeout")
                .isEqualTo(policy.fallback("pkg.name busy_timeout 怎麼設定"));
        assertThat(ScopedDocumentQueryFallbackPolicy.VERSION)
                .isEqualTo("scoped-document-query-fallback-v1");
    }
}
