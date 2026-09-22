package org.km.llmwiki.search;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded, provider-neutral fallback for a zero-hit lexical query scoped to one document.
 *
 * <p>v2 is document-local: besides exact technical anchors, it only retains query terms that
 * actually occur in the scoped document's eligible vocabulary. No hard-coded conversational
 * prefix/suffix allowlist is used, so arbitrary paraphrase orderings and unseen filler terms
 * are handled without overfitting to canned phrasing. The caller remains responsible for
 * running the resulting query through the normal workspace, document, freshness,
 * currentness, and authority gates.
 */
@Component
final class ScopedDocumentQueryFallbackPolicy {

    static final String VERSION = "scoped-document-query-fallback-v2";
    private static final int MAX_TECHNICAL_ANCHORS = 4;
    private static final int MIN_RETAINED_TERMS = 2;

    private static final Pattern TECHNICAL_ANCHOR = Pattern.compile(
            "(?iu)(?:[A-Z]{2,}-\\d{3,}|[A-Z][A-Za-z0-9_$]*(?:Error|Exception)"
                    + "|[a-z][a-z0-9_]*_[a-z0-9_]+"
                    + "|[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+)");

    Optional<String> fallback(String originalQuery) {
        return fallback(originalQuery, Set.of());
    }

    /**
     * Document-local reduction: retains only original projected terms present in the scoped
     * document vocabulary, in original order. Technical anchors keep priority for exact
     * preservation; otherwise the retained subset must be a strict reduction. Single-term
     * fallbacks are only allowed for distinctive Latin/digit tokens to avoid broadening
     * generic CJK bigrams into false evidence.
     */
    Optional<String> fallback(String originalQuery, Set<String> documentTerms) {
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

        if (documentTerms == null || documentTerms.isEmpty()) {
            return Optional.empty();
        }
        LinkedHashSet<String> retained = new LinkedHashSet<>();
        for (String term : originalTerms) {
            if (documentTerms.contains(term)) {
                retained.add(term);
            }
        }
        if (retained.size() >= originalTerms.size()) {
            return Optional.empty();
        }
        if (retained.size() < MIN_RETAINED_TERMS && !isDistinctiveSingleRetention(retained)) {
            return Optional.empty();
        }
        boolean hasSubstantiveTerm = retained.stream()
                .anyMatch(term -> term.codePointCount(0, term.length()) >= 2);
        if (!hasSubstantiveTerm) {
            return Optional.empty();
        }
        String candidate = String.join(" ", retained);
        if (!isStrictReduction(normalized, originalTerms, candidate)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
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

    private static boolean isDistinctiveSingleRetention(Set<String> retained) {
        if (retained.size() != 1) {
            return false;
        }
        String term = retained.iterator().next();
        if (term.codePointCount(0, term.length()) < 4) {
            return false;
        }
        return term.codePoints().anyMatch(codePoint ->
                (codePoint >= 'a' && codePoint <= 'z')
                        || (codePoint >= 'A' && codePoint <= 'Z')
                        || (codePoint >= '0' && codePoint <= '9'));
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
