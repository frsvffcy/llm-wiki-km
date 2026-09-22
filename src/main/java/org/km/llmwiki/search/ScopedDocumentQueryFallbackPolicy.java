package org.km.llmwiki.search;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded, provider-neutral fallback for a zero-hit lexical query scoped to one document.
 *
 * <p>The policy never changes global FTS semantics. It preserves exact technical anchors when
 * present; otherwise it may remove one application-owned conversational prefix and suffix. The
 * caller remains responsible for running the resulting query through the normal workspace,
 * document, freshness, currentness, and authority gates.
 */
@Component
final class ScopedDocumentQueryFallbackPolicy {

    static final String VERSION = "scoped-document-query-fallback-v1";
    private static final int MAX_TECHNICAL_ANCHORS = 4;

    private static final Pattern TECHNICAL_ANCHOR = Pattern.compile(
            "(?iu)(?:[A-Z]{2,}-\\d{3,}|[A-Z][A-Za-z0-9_$]*(?:Error|Exception)"
                    + "|[a-z][a-z0-9_]*_[a-z0-9_]+"
                    + "|[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+)");

    private static final List<String> PREFIXES = List.of(
            "可以告訴我", "我想知道", "請問", "想請問",
            "what is ", "what are ", "how do i ", "how to ");
    private static final List<String> SUFFIXES = List.of(
            "要怎麼設定", "要怎麼調整", "該怎麼設定", "該怎麼調整",
            "怎麼設定", "怎麼調整", "如何設定", "如何調整",
            "要怎麼使用", "該怎麼使用", "怎麼使用", "如何使用",
            "是什麼意思", "有什麼用途", "如何運作", "怎麼運作", "是什麼",
            " please");

    Optional<String> fallback(String originalQuery) {
        if (originalQuery == null || originalQuery.isBlank()) {
            return Optional.empty();
        }
        String normalized = Normalizer.normalize(originalQuery.strip(), Normalizer.Form.NFC);
        List<String> originalTerms = CjkBigramProjector.tokens(normalized);
        if (originalTerms.size() < 2) {
            return Optional.empty();
        }

        Optional<String> technical = technicalAnchors(normalized);
        if (technical.isPresent() && isStrictReduction(normalized, originalTerms, technical.get())) {
            return technical;
        }

        String reduced = removeConversationalAffixes(normalized);
        if (!isStrictReduction(normalized, originalTerms, reduced)) {
            return Optional.empty();
        }
        return Optional.of(reduced);
    }

    private static Optional<String> technicalAnchors(String query) {
        Matcher matcher = TECHNICAL_ANCHOR.matcher(query);
        Map<String, String> anchors = new LinkedHashMap<>();
        while (matcher.find() && anchors.size() < MAX_TECHNICAL_ANCHORS) {
            String anchor = matcher.group();
            anchors.putIfAbsent(anchor.toLowerCase(Locale.ROOT), anchor);
        }
        return anchors.isEmpty()
                ? Optional.empty() : Optional.of(String.join(" ", anchors.values()));
    }

    private static String removeConversationalAffixes(String query) {
        String reduced = removeQuestionEnding(query);
        String lower = reduced.toLowerCase(Locale.ROOT);
        for (String prefix : PREFIXES) {
            if (lower.startsWith(prefix)) {
                reduced = reduced.substring(prefix.length()).strip();
                break;
            }
        }
        lower = reduced.toLowerCase(Locale.ROOT);
        for (String suffix : SUFFIXES) {
            if (lower.endsWith(suffix)) {
                reduced = reduced.substring(0, reduced.length() - suffix.length()).strip();
                break;
            }
        }
        return removeQuestionEnding(reduced);
    }

    private static String removeQuestionEnding(String value) {
        String reduced = value;
        while (reduced.endsWith("？") || reduced.endsWith("?")
                || reduced.endsWith("嗎") || reduced.endsWith("呢")) {
            reduced = reduced.substring(0, reduced.length() - 1).strip();
        }
        return reduced;
    }

    private static boolean isStrictReduction(String original, List<String> originalTerms,
                                             String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.equals(original)) {
            return false;
        }
        List<String> candidateTerms = CjkBigramProjector.tokens(candidate);
        if (candidateTerms.isEmpty() || candidateTerms.size() >= originalTerms.size()) {
            return false;
        }
        Map<String, Integer> remaining = new HashMap<>();
        originalTerms.forEach(term -> remaining.merge(term, 1, Integer::sum));
        for (String term : candidateTerms) {
            int available = remaining.getOrDefault(term, 0);
            if (available == 0) {
                return false;
            }
            remaining.put(term, available - 1);
        }
        // Reuse the canonical validation boundary so a fallback cannot evade query limits.
        FtsMatchQuery.literalExpression(candidate);
        return true;
    }
}
