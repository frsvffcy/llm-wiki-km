package org.km.llmwiki.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Evaluation-only deterministic second-stage rerank strategies for the #316 reranking
 * evaluation. A policy reorders the already-qualified {@code EvidenceBundle} items; it can
 * never add, drop, or re-identify evidence, never touches authority/currentness/citation
 * contracts, and must be exactly reproducible for the same inputs. Strategies here are pure
 * Java string/set computations over the application-owned evidence content and provenance —
 * no model, no network, no vendor score.
 */
final class SecondStageRerankPolicies {

    private static final Pattern TECHNICAL_TOKEN = Pattern.compile(
            "([A-Z]{2,}[-_]?\\d+|[A-Z][a-z]+[A-Z][a-z]+|[a-z]+(\\.[a-z]+)+|\\b[A-Za-z]+[-][a-z]+)");

    private SecondStageRerankPolicies() {
    }

    /** A rerank policy reorders baseline-qualified items; ordering must be total and stable. */
    interface SecondStageRerankPolicy {
        String name();

        List<EvidenceItem> rerank(String query, List<EvidenceItem> baselineOrder);
    }

    static SecondStageRerankPolicy noRerank() {
        return new SecondStageRerankPolicy() {
            @Override
            public String name() {
                return "NO_RERANK";
            }

            @Override
            public List<EvidenceItem> rerank(String query, List<EvidenceItem> baselineOrder) {
                return List.copyOf(baselineOrder);
            }
        };
    }

    /**
     * Exact-anchor reranking: technical-token and title/heading exact hits dominate, CJK
     * bigram overlap next, plain token coverage last; ties fall back to the baseline order so
     * the policy can never invent an arbitrary permutation.
     */
    static SecondStageRerankPolicy exactAnchor() {
        return new SecondStageRerankPolicy() {
            @Override
            public String name() {
                return "rerank-v1-exact-anchor";
            }

            @Override
            public List<EvidenceItem> rerank(String query, List<EvidenceItem> baselineOrder) {
                Set<String> queryTokens = tokens(query);
                Set<String> queryBigrams = bigrams(query);
                Set<String> technical = technicalTokens(query);
                List<Scored> scored = new ArrayList<>();
                for (int index = 0; index < baselineOrder.size(); index++) {
                    EvidenceItem item = baselineOrder.get(index);
                    String haystack = (item.title() == null ? "" : item.title() + " ") + " "
                            + item.content() + " " + provenance(item);
                    Set<String> itemTokens = tokens(haystack);
                    Set<String> itemBigrams = bigrams(haystack);
                    double score = 0.0d;
                    for (String token : technical) {
                        if (itemTokens.contains(token)) {
                            score += 4.0d;
                        }
                    }
                    if (item.title() != null && !item.title().isBlank()) {
                        Set<String> titleTokens = tokens(item.title());
                        for (String token : queryTokens) {
                            if (titleTokens.contains(token)) {
                                score += 2.0d;
                            }
                        }
                    }
                    double bigramHits = overlap(queryBigrams, itemBigrams);
                    score += 1.5d * bigramHits / Math.max(1, queryBigrams.size());
                    score += 1.0d * overlap(queryTokens, itemTokens)
                            / Math.max(1, queryTokens.size());
                    scored.add(new Scored(item, score, index));
                }
                return order(scored);
            }
        };
    }

    /**
     * Coverage-blend reranking: pure token/bigram coverage blended with the baseline rank, so
     * the policy refines ordering without fully overriding the production fusion.
     */
    static SecondStageRerankPolicy coverageBlend() {
        return new SecondStageRerankPolicy() {
            @Override
            public String name() {
                return "rerank-v1-coverage-blend";
            }

            @Override
            public List<EvidenceItem> rerank(String query, List<EvidenceItem> baselineOrder) {
                Set<String> queryTokens = tokens(query);
                Set<String> queryBigrams = bigrams(query);
                List<Scored> scored = new ArrayList<>();
                for (int index = 0; index < baselineOrder.size(); index++) {
                    EvidenceItem item = baselineOrder.get(index);
                    String haystack = item.content() + " " + (item.title() == null ? ""
                            : item.title()) + " " + provenance(item);
                    Set<String> itemTokens = tokens(haystack);
                    Set<String> itemBigrams = bigrams(haystack);
                    double coverage = 0.5d * overlap(queryTokens, itemTokens)
                            / Math.max(1, queryTokens.size())
                            + 0.5d * overlap(queryBigrams, itemBigrams)
                                    / Math.max(1, queryBigrams.size());
                    double score = 0.6d * coverage + 0.4d / (index + 1);
                    scored.add(new Scored(item, score, index));
                }
                return order(scored);
            }
        };
    }

    private static List<EvidenceItem> order(List<Scored> scored) {
        List<Scored> sorted = new ArrayList<>(scored);
        sorted.sort((left, right) -> {
            int byScore = Double.compare(right.score(), left.score());
            return byScore != 0 ? byScore : Integer.compare(left.baselineIndex(),
                    right.baselineIndex());
        });
        return sorted.stream().map(Scored::item).toList();
    }

    private static String provenance(EvidenceItem item) {
        List<String> parts = new ArrayList<>();
        if (item.title() != null) {
            parts.add(item.title());
        }
        if (item.path() != null) {
            parts.add(item.path());
        }
        if (item.section() != null) {
            parts.add(item.section());
        }
        if (item.headingPath() != null) {
            parts.add(item.headingPath());
        }
        if (item.documentName() != null) {
            parts.add(item.documentName());
        }
        return String.join(" ", parts);
    }

    private static double overlap(Set<String> left, Set<String> right) {
        int hits = 0;
        for (String token : left) {
            if (right.contains(token)) {
                hits++;
            }
        }
        return hits;
    }

    private static Set<String> tokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder latin = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint) || codePoint == '-') {
                latin.appendCodePoint(Character.toLowerCase(codePoint));
            } else {
                flush(latin, tokens);
            }
        }
        flush(latin, tokens);
        tokens.addAll(cjkBigrams(text));
        return tokens;
    }

    private static void flush(StringBuilder builder, Set<String> tokens) {
        if (!builder.isEmpty()) {
            tokens.add(builder.toString());
            builder.setLength(0);
        }
    }

    private static Set<String> bigrams(String text) {
        return new HashSet<>(cjkBigrams(text));
    }

    private static List<String> cjkBigrams(String text) {
        List<String> bigrams = new ArrayList<>();
        String normalized = text.toLowerCase(Locale.ROOT);
        int previous = -1;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isIdeographic(codePoint)) {
                if (previous >= 0) {
                    bigrams.add(new String(Character.toChars(previous))
                            + new String(Character.toChars(codePoint)));
                }
                previous = codePoint;
            } else {
                previous = -1;
            }
        }
        return bigrams;
    }

    private static boolean isIdeographic(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF);
    }

    private static Set<String> technicalTokens(String text) {
        Set<String> found = new LinkedHashSet<>();
        var matcher = TECHNICAL_TOKEN.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return found;
    }

    private record Scored(EvidenceItem item, double score, int baselineIndex) {
    }
}
