package org.km.llmwiki.search;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("contract")
class ScopedDocumentQueryFallbackPolicyTest {

    private final ScopedDocumentQueryFallbackPolicy policy =
            new ScopedDocumentQueryFallbackPolicy();

    private static final String DOCUMENT_CONTENT = """
            busy_timeout 控制資料庫鎖定時的等待毫秒數。
            知識管理需要明確的來源與可追蹤引用。
            exactbodytoken 只存在於這份文件的正文。
            """;

    private static Set<String> documentVocabulary() {
        Set<String> vocabulary = new HashSet<>();
        for (String line : DOCUMENT_CONTENT.split("\n")) {
            vocabulary.addAll(CjkBigramProjector.tokens(line));
        }
        return Set.copyOf(vocabulary);
    }

    private static Set<String> vocabularyOf(String... contents) {
        Set<String> vocabulary = new HashSet<>();
        for (String content : contents) {
            vocabulary.addAll(CjkBigramProjector.tokens(content));
        }
        return Set.copyOf(vocabulary);
    }

    @Test
    void preservesExactTechnicalAnchorsAndDropsUnmatchedFiller() {
        assertThat(policy.fallback("busy_timeout 這個設定要怎麼調整", documentVocabulary()))
                .contains("busy_timeout");
        assertThat(policy.fallback("busy_timeout unicorn", documentVocabulary()))
                .contains("busy_timeout");
        assertThat(policy.fallback("ORA-12899 發生時要怎麼調整",
                vocabularyOf("ORA-12899 發生時的處理步驟")))
                .contains("ORA-12899");
        assertThat(policy.fallback("NoSuchMethodError 怎麼處理",
                vocabularyOf("NoSuchMethodError 的堆疊追蹤")))
                .contains("NoSuchMethodError");
    }

    @Test
    void recoversParaphrasedCjkQuestionsWithoutFixedAffixAllowlist() {
        Set<String> vocabulary = documentVocabulary();
        // Canned shapes from v1 must keep working through document-local retention.
        assertThat(policy.fallback("請問知識管理如何運作？", vocabulary))
                .isPresent();
        assertThat(policy.fallback("知識管理要怎麼設定", vocabulary))
                .isPresent();
        // New paraphrases with unseen ordering and filler not present in the source.
        List<String> paraphrases = List.of(
                "知識管理到底該如何理解",
                "關於知識管理，到底該如何理解呢？",
                "可以分享一些關於知識管理的看法嗎",
                "這份文件的知識管理是在講什麼東西",
                "知識管理 unicorn 外星詞到底是什麼樣的概念",
                "那個控制鎖定等待的 busy_timeout 要去哪裡改",
                "busy_timeout 這個參數背後的等待機制是什麼",
                "exactbodytoken 相關的說明在哪裡可以找到");
        for (String paraphrase : paraphrases) {
            assertThat(policy.fallback(paraphrase, vocabulary))
                    .as("paraphrase should reduce to document-local terms: %s", paraphrase)
                    .isPresent();
        }
        // Retained fallback must only contain document-local terms.
        for (String paraphrase : paraphrases) {
            String fallback = policy.fallback(paraphrase, vocabulary).orElseThrow();
            List<String> fallbackTerms = CjkBigramProjector.tokens(fallback);
            assertThat(fallbackTerms)
                    .as("fallback terms must exist in document vocabulary: %s", paraphrase)
                    .allSatisfy(term -> assertThat(vocabulary).contains(term));
            assertThat(fallbackTerms.size())
                    .as("fallback must be a strict reduction: %s", paraphrase)
                    .isLessThan(CjkBigramProjector.tokens(paraphrase).size());
        }
    }

    @Test
    void doesNotTurnCorpusMismatchIntoForcedEvidence() {
        Set<String> vocabulary = documentVocabulary();
        assertThat(policy.fallback("alpha unicorn", vocabulary)).isEmpty();
        assertThat(policy.fallback("完全無關的自然語句", vocabulary)).isEmpty();
        assertThat(policy.fallback("外星科技與量子傳送的原理是什麼", vocabulary)).isEmpty();
        assertThat(policy.fallback("busy_timeout", vocabulary)).isEmpty();
        assertThat(policy.fallback("請問是什麼？", vocabulary)).isEmpty();
        // Filename-only terms are not part of content vocabulary and must not fallback.
        assertThat(policy.fallback("filenameonlymarker", vocabulary)).isEmpty();
        assertThat(policy.fallback("filenameonlymarker 請問是什麼", vocabulary)).isEmpty();
        // Single retained bigram must not broaden to a one-term query.
        Set<String> narrowVocabulary = vocabularyOf("管理 設定說明");
        assertThat(policy.fallback("管理 外星詞彙完全無關", narrowVocabulary)).isEmpty();
        // Empty or foreign vocabulary never guides a fallback beyond technical anchors.
        assertThat(policy.fallback("知識管理到底該如何理解", Set.of())).isEmpty();
        assertThat(policy.fallback("知識管理到底該如何理解", null)).isEmpty();
    }

    @Test
    void isDeterministicAndEmitsAtMostOneFallbackQuery() {
        Set<String> vocabulary = documentVocabulary();
        assertThat(policy.fallback("pkg.name busy_timeout 怎麼設定", vocabulary))
                .contains("pkg.name busy_timeout")
                .isEqualTo(policy.fallback("pkg.name busy_timeout 怎麼設定", vocabulary));
        assertThat(policy.fallback("知識管理到底該如何理解", vocabulary))
                .isEqualTo(policy.fallback("知識管理到底該如何理解", vocabulary));
        assertThat(ScopedDocumentQueryFallbackPolicy.VERSION)
                .isEqualTo("scoped-document-query-fallback-v2");
    }
}
