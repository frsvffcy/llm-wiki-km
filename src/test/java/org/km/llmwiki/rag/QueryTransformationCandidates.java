package org.km.llmwiki.rag;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Evaluation-only typed contract for query-side transformation candidates (#390). Nothing here
 * is production code and nothing here touches the Ask path: the harness plans the retrieval
 * inputs for each candidate and executes them through the unchanged production
 * {@link RetrievalService}, so every candidate competes on the same authority/currentness,
 * fusion and budget semantics.
 *
 * <p>The contract models what a production adoption would have to guarantee (issue #390
 * Versioning/Adoption contract): the original query is always the first retrieval input and is
 * never dropped, provider output is only ever a bounded retrieval-input candidate (never
 * authority, never an answer, never a mode/path/SQL directive), and every failure mode degrades
 * deterministically to the original query with a typed reason instead of failing the Ask.
 * Output bounds mirror the production FTS boundary (256 code points, 64 projected terms,
 * no control characters) so a rejected rewrite can never break the retrieval it feeds.
 */
final class QueryTransformationCandidates {

    /** Hard output bounds, mirroring the production FTS query boundary (256 cp / 64 terms). */
    static final int MAX_REWRITE_CODE_POINTS = 256;
    static final int MAX_REWRITE_PROJECTED_TERMS = 64;
    /** Hard fan-out budget: original query + at most two transformed variants. */
    static final int MAX_FAN_OUT = 3;

    private QueryTransformationCandidates() {
    }

    /** Simulated provider boundary; a production adoption would put a real LLM behind this. */
    interface RewriteProvider {
        RewriteReply rewrite(String queryId, String originalQuestion);
    }

    enum RewriteReplyStatus {
        REWRITTEN, NO_REWRITE, UNAVAILABLE, MALFORMED_OUTPUT
    }

    record RewriteReply(String rewrittenQuery, RewriteReplyStatus status, String detail) {
    }

    enum EventKind {
        REWRITE_APPLIED,
        NO_REWRITE_PROVIDED,
        NO_OP_DUPLICATE_OF_ORIGINAL,
        FALLBACK_PROVIDER_UNAVAILABLE,
        FALLBACK_MALFORMED_OUTPUT,
        FALLBACK_OVER_LIMIT,
        FAN_OUT_CAPPED
    }

    record TransformationEvent(EventKind kind, String detail) {
    }

    record RetrievalInput(String queryText, String origin) {
        static final String ORIGIN_ORIGINAL = "ORIGINAL";
        static final String ORIGIN_REWRITE = "REWRITE";
    }

    record TransformationPlan(String candidate, String originalQuery, List<RetrievalInput> inputs,
                              List<TransformationEvent> events, int providerCallAttempts) {
    }

    interface Candidate {
        String name();

        TransformationPlan plan(String queryId, String originalQuery);
    }

    /** ORIGINAL_QUERY: the current production behavior — the question is used verbatim. */
    static Candidate originalQuery() {
        return new Candidate() {
            @Override
            public String name() {
                return "ORIGINAL_QUERY";
            }

            @Override
            public TransformationPlan plan(String queryId, String originalQuery) {
                return new TransformationPlan(name(), originalQuery,
                        List.of(new RetrievalInput(originalQuery, RetrievalInput.ORIGIN_ORIGINAL)),
                        List.of(), 0);
            }
        };
    }

    /**
     * SINGLE_REWRITE: the original query always executes first (traceable, baseline never
     * lost), then at most one validated rewrite. Any provider failure — unavailable, malformed,
     * over-limit, duplicate of the original, or a thrown transport error — degrades to the
     * original query with a typed event.
     */
    static Candidate singleRewrite(String name, RewriteProvider provider) {
        return new Candidate() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public TransformationPlan plan(String queryId, String originalQuery) {
                List<RetrievalInput> inputs = new ArrayList<>();
                inputs.add(new RetrievalInput(originalQuery, RetrievalInput.ORIGIN_ORIGINAL));
                List<TransformationEvent> events = new ArrayList<>();
                applyRewrite(provider, queryId, originalQuery, inputs, events);
                return new TransformationPlan(name(), originalQuery, List.copyOf(inputs),
                        List.copyOf(events), 1);
            }
        };
    }

    /**
     * MULTI_QUERY_BOUNDED: original + at most two validated rewrite variants, deduplicated
     * against the original and each other. Only evaluated when the SINGLE_REWRITE unlock rule
     * fires; the fan-out budget is a hard contract of this harness.
     */
    static Candidate multiQueryBounded(RewriteProvider primary, RewriteProvider alternate) {
        return new Candidate() {
            @Override
            public String name() {
                return "MULTI_QUERY_BOUNDED";
            }

            @Override
            public TransformationPlan plan(String queryId, String originalQuery) {
                List<RetrievalInput> inputs = new ArrayList<>();
                inputs.add(new RetrievalInput(originalQuery, RetrievalInput.ORIGIN_ORIGINAL));
                List<TransformationEvent> events = new ArrayList<>();
                applyRewrite(primary, queryId, originalQuery, inputs, events);
                applyRewrite(alternate, queryId, originalQuery, inputs, events);
                if (inputs.size() > MAX_FAN_OUT) {
                    events.add(new TransformationEvent(EventKind.FAN_OUT_CAPPED,
                            "fan-out hard budget " + MAX_FAN_OUT + " enforced"));
                    inputs = new ArrayList<>(inputs.subList(0, MAX_FAN_OUT));
                }
                return new TransformationPlan(name(), originalQuery, List.copyOf(inputs),
                        List.copyOf(events), 2);
            }
        };
    }

    private static void applyRewrite(RewriteProvider provider, String queryId,
                                     String originalQuery, List<RetrievalInput> inputs,
                                     List<TransformationEvent> events) {
        RewriteReply reply;
        try {
            reply = provider.rewrite(queryId, originalQuery);
        } catch (RuntimeException failure) {
            events.add(new TransformationEvent(EventKind.FALLBACK_PROVIDER_UNAVAILABLE,
                    "provider threw: " + failure.getClass().getSimpleName()));
            return;
        }
        switch (reply.status()) {
            case UNAVAILABLE -> events.add(new TransformationEvent(
                    EventKind.FALLBACK_PROVIDER_UNAVAILABLE, reply.detail()));
            case NO_REWRITE -> events.add(new TransformationEvent(
                    EventKind.NO_REWRITE_PROVIDED, reply.detail()));
            case MALFORMED_OUTPUT -> events.add(new TransformationEvent(
                    EventKind.FALLBACK_MALFORMED_OUTPUT, reply.detail()));
            case REWRITTEN -> {
                ValidatedRewrite validated = validateRewriteOutput(reply.rewrittenQuery(),
                        originalQuery, inputs);
                if (validated.rejection() == null) {
                    events.add(new TransformationEvent(EventKind.REWRITE_APPLIED,
                            validated.normalized()));
                    inputs.add(new RetrievalInput(validated.normalized(),
                            RetrievalInput.ORIGIN_REWRITE));
                } else {
                    events.add(new TransformationEvent(
                            validated.rejection().endsWith("duplicate-of-original")
                                    ? EventKind.NO_OP_DUPLICATE_OF_ORIGINAL
                                    : validated.rejection().startsWith("over-")
                                            ? EventKind.FALLBACK_OVER_LIMIT
                                            : EventKind.FALLBACK_MALFORMED_OUTPUT,
                            validated.rejection()));
                }
            }
        }
    }

    private record ValidatedRewrite(String normalized, String rejection) {
    }

    /**
     * Bounded validation of provider output against the production FTS boundary; returns a
     * {@code null} rejection when the rewrite may be used, otherwise a stable rejection code.
     * A rewrite that normalizes (NFC) to the original query, or projects to the same tokens, is
     * a typed duplicate no-op instead of a second identical retrieval.
     */
    private static ValidatedRewrite validateRewriteOutput(String raw, String originalQuery,
                                                          List<RetrievalInput> inputs) {
        if (raw == null || raw.isBlank()) {
            return new ValidatedRewrite(null, "malformed: blank rewrite");
        }
        String normalized = Normalizer.normalize(raw.strip(), Normalizer.Form.NFC);
        if (normalized.codePointCount(0, normalized.length()) > MAX_REWRITE_CODE_POINTS) {
            return new ValidatedRewrite(null,
                    "over-code-points: rewrite exceeds " + MAX_REWRITE_CODE_POINTS);
        }
        if (normalized.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                && !Character.isWhitespace(codePoint))) {
            return new ValidatedRewrite(null, "malformed: control characters");
        }
        List<String> tokens = org.km.llmwiki.search.CjkBigramProjector.tokens(normalized);
        if (tokens.isEmpty()) {
            return new ValidatedRewrite(null, "malformed: no searchable terms");
        }
        if (tokens.size() > MAX_REWRITE_PROJECTED_TERMS) {
            return new ValidatedRewrite(null,
                    "over-projected-terms: rewrite exceeds " + MAX_REWRITE_PROJECTED_TERMS);
        }
        Set<String> knownInputs = new LinkedHashSet<>();
        for (RetrievalInput input : inputs) {
            knownInputs.addAll(org.km.llmwiki.search.CjkBigramProjector.tokens(input.queryText()));
        }
        if (new LinkedHashSet<>(tokens).equals(knownInputs)
                || normalized.equals(Normalizer.normalize(originalQuery.strip(),
                        Normalizer.Form.NFC))) {
            return new ValidatedRewrite(null, "no-op: duplicate-of-original");
        }
        return new ValidatedRewrite(normalized, null);
    }

    // ------------------------------------------------------------------ fixture providers

    /** Versioned corpus rewrite fixtures (protected candidate). */
    static RewriteProvider corpusProtectedProvider() {
        return fixtureBacked(QueryTransformationEvaluationCorpusV1.RewriteFixture::protectedRewrite);
    }

    /** Second plausible phrasing; only consumed by the bounded multi-query candidate. */
    static RewriteProvider corpusAlternateProvider() {
        return fixtureBacked(QueryTransformationEvaluationCorpusV1.RewriteFixture::altRewrite);
    }

    /** Degradation probe: token-normalizing rewrites where defined, protected otherwise. */
    static RewriteProvider unprotectedProbeProvider() {
        return fixtureBacked(fixture -> fixture.unprotectedProbe() != null
                ? fixture.unprotectedProbe() : fixture.protectedRewrite());
    }

    static RewriteProvider unavailableProvider() {
        return (queryId, question) -> new RewriteReply(null, RewriteReplyStatus.UNAVAILABLE,
                "simulated provider outage");
    }

    static RewriteProvider malformedProvider() {
        return (queryId, question) -> new RewriteReply("   ", RewriteReplyStatus.REWRITTEN,
                "simulated blank output");
    }

    static RewriteProvider duplicateProvider() {
        return (queryId, question) -> new RewriteReply(question, RewriteReplyStatus.REWRITTEN,
                "simulated echo of the original question");
    }

    static RewriteProvider overLimitProvider() {
        return (queryId, question) -> new RewriteReply("改".repeat(MAX_REWRITE_CODE_POINTS + 44),
                RewriteReplyStatus.REWRITTEN, "simulated over-bound output");
    }

    private static RewriteProvider fixtureBacked(
            java.util.function.Function<QueryTransformationEvaluationCorpusV1.RewriteFixture,
                    String> column) {
        return (queryId, question) -> {
            QueryTransformationEvaluationCorpusV1.RewriteFixture fixture =
                    QueryTransformationEvaluationCorpusV1.fixtureFor(queryId);
            String rewrite = fixture == null ? null : column.apply(fixture);
            if (rewrite == null) {
                return new RewriteReply(null, RewriteReplyStatus.NO_REWRITE,
                        "no rewrite fixture for " + queryId);
            }
            return new RewriteReply(rewrite, RewriteReplyStatus.REWRITTEN,
                    fixture == null ? "evaluation fixture" : fixture.rationale());
        };
    }
}
