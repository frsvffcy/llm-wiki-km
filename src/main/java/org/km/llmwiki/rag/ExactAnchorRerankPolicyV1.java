package org.km.llmwiki.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Production second-stage reranking policy adopted from the #316 evaluation winner
 * ({@code rerank-policy-v1-exact-anchor}; CONDITIONAL GO). Exact technical-token and
 * title/heading anchor hits dominate, CJK bigram overlap ({@code cjk-bigram-v1}) comes next,
 * and plain token coverage last; ties fall back to the application-owned baseline order so the
 * policy never invents an arbitrary permutation. Pure Java string/set computation over the
 * qualified evidence content and provenance: no model, no network, no vendor score, no wall
 * clock, no process-local state. The policy only reorders; the canonical identity set, citation
 * identity, content hashes, and provenance are never touched.
 */
@org.springframework.stereotype.Component
public final class ExactAnchorRerankPolicyV1 implements SecondStageRerankPolicy {

    public static final String VERSION = "rerank-policy-v1-exact-anchor";

    private static final java.util.regex.Pattern TECHNICAL_TOKEN =
            java.util.regex.Pattern.compile(
                    "([A-Z]{2,}[-_]?\\d+|[A-Z][a-z]+[A-Z][a-z]+|[a-z]+(\\.[a-z]+)+|\\b[A-Za-z]+[-][a-z]+)");

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public RerankResult apply(EvidenceBundle evidence) {
        if (evidence.items().size() < 2) {
            return new RerankResult(evidence,
                    RerankStatus.NO_OP_INSUFFICIENT_CANDIDATES,
                    RerankNoOpReason.INSUFFICIENT_CANDIDATES, VERSION);
        }
        String query = evidence.query();
        Set<String> queryTokens = tokens(query);
        Set<String> queryBigrams = bigrams(query);
        Set<String> technical = technicalTokens(query);

        List<Scored> scored = new ArrayList<>();
        for (int index = 0; index < evidence.items().size(); index++) {
            EvidenceItem item = evidence.items().get(index);
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
            score += 1.0d * overlap(queryTokens, itemTokens) / Math.max(1, queryTokens.size());
            scored.add(new Scored(item, score, index));
        }

        List<Scored> ordered = new ArrayList<>(scored);
        ordered.sort((left, right) -> {
            int byScore = Double.compare(right.score(), left.score());
            return byScore != 0 ? byScore : Integer.compare(left.baselineIndex(),
                    right.baselineIndex());
        });
        List<EvidenceItem> items = ordered.stream().map(Scored::item).toList();
        // Structural honesty gate: the policy itself must never change the identity set.
        if (!sameIdentitySet(evidence.items(), items)) {
            return new RerankResult(evidence,
                    RerankStatus.NO_OP_UNSUPPORTED_SHAPE,
                    RerankNoOpReason.UNSUPPORTED_QUERY_SHAPE, VERSION);
        }
        EvidenceBundle orderedView = new EvidenceBundle(evidence.query(), evidence.mode(),
                evidence.workspace(), items, evidence.budget(),
                evidence.searchedCandidateCount(), evidence.rejectedCandidateCount(),
                evidence.insufficientEvidence(), evidence.diagnostics());
        return new RerankResult(orderedView, RerankStatus.APPLIED, null, VERSION);
    }

    private static boolean sameIdentitySet(List<EvidenceItem> baseline, List<EvidenceItem> ordered) {
        if (baseline.size() != ordered.size()) {
            return false;
        }
        Set<String> baselineIdentities = new LinkedHashSet<>();
        baseline.forEach(item -> baselineIdentities.add(item.stableIdentity()));
        Set<String> orderedIdentities = new LinkedHashSet<>();
        ordered.forEach(item -> orderedIdentities.add(item.stableIdentity()));
        return baselineIdentities.equals(orderedIdentities);
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
        tokens.addAll(bigrams(text));
        return tokens;
    }

    private static void flush(StringBuilder builder, Set<String> tokens) {
        if (!builder.isEmpty()) {
            tokens.add(builder.toString());
            builder.setLength(0);
        }
    }

    private static Set<String> bigrams(String text) {
        return new LinkedHashSet<>(cjkBigrams(text));
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
