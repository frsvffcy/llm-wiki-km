package org.km.llmwiki.ai.query;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.answer.ProviderUsageStatus;
import org.km.llmwiki.ai.answer.AnswerUsageMetadata;
import org.km.llmwiki.rag.EvidenceBudget;
import org.km.llmwiki.rag.EvidenceBundle;
import org.km.llmwiki.rag.EvidenceItem;
import org.km.llmwiki.rag.EvidenceKind;
import org.km.llmwiki.rag.EvidenceWorkspace;
import org.km.llmwiki.rag.RetrievalDiagnostics;
import org.km.llmwiki.rag.RetrievalMode;
import org.km.llmwiki.rag.ModalityOutcome;
import org.km.llmwiki.rag.RetrievalRequest;
import org.km.llmwiki.rag.RetrievalService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
class QueryTransformationServiceTest {
    private static final EvidenceWorkspace WORKSPACE = new EvidenceWorkspace(7, "wiki");
    private static final RetrievalRequest REQUEST = RetrievalRequest.defaults(
            "資料庫連線的 busy_timeout 預設值要怎麼設定？", RetrievalMode.HYBRID_VECTOR);

    @Test
    void originalQueryIsFirstAndSingleRewriteHardCapsFanOutAtTwo() {
        List<String> inputs = new ArrayList<>();
        RetrievalService retrieval = mock(RetrievalService.class);
        EvidenceBundle original = bundle("original", List.of(item("original")));
        EvidenceBundle rewritten = bundle("資料庫 busy_timeout", List.of(item("target")));
        when(retrieval.retrieve(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            RetrievalRequest request = invocation.getArgument(0);
            inputs.add(request.query());
            return rewritten;
        });
        QueryTransformationService service = new QueryTransformationService(
                new SingleRewritePolicyV1(), (query, protectedTokens) -> {
                    assertThat(protectedTokens).containsExactly("busy_timeout");
                    return "資料庫 busy_timeout";
                });

        // Ask has already executed the original retrieval before entering this boundary.
        inputs.add(REQUEST.query());
        QueryTransformationResult result = service.apply(REQUEST, original, retrieval);

        assertThat(inputs).containsExactly(REQUEST.query(), "資料庫 busy_timeout");
        assertThat(result.execution().retrievalInputCount()).isEqualTo(2);
        assertThat(result.execution().status())
                .isEqualTo(QueryTransformationStatus.REWRITE_APPLIED);
        assertThat(result.evidence().items()).extracting(EvidenceItem::stableIdentity)
                .containsExactly("WIKI:original", "WIKI:target");
    }

    @Test
    void providerFailuresAndInvalidOutputsFallBackToOriginalEvidence() {
        List<Scenario> scenarios = List.of(
                new Scenario((query, tokens) -> { throw new QueryRewriteException(
                        QueryRewriteException.Type.UNAVAILABLE, "offline"); },
                        QueryTransformationStatus.FALLBACK_PROVIDER_UNAVAILABLE),
                new Scenario((query, tokens) -> { throw new QueryRewriteException(
                        QueryRewriteException.Type.INVALID_RESPONSE, "invalid"); },
                        QueryTransformationStatus.FALLBACK_PROVIDER_INVALID),
                new Scenario((query, tokens) -> null,
                        QueryTransformationStatus.FALLBACK_PROVIDER_INVALID),
                new Scenario((query, tokens) -> "x".repeat(257) + " busy_timeout",
                        QueryTransformationStatus.FALLBACK_OUTPUT_OVER_LIMIT),
                new Scenario((query, tokens) -> query,
                        QueryTransformationStatus.NO_OP_DUPLICATE),
                new Scenario((query, tokens) -> "資料庫連線逾時",
                        QueryTransformationStatus.FALLBACK_EXACT_TOKEN_LOSS));
        EvidenceBundle original = bundle("original", List.of(item("original")));

        for (Scenario scenario : scenarios) {
            AtomicInteger retrievalCalls = new AtomicInteger();
            RetrievalService retrieval = mock(RetrievalService.class);
            when(retrieval.retrieve(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                retrievalCalls.incrementAndGet();
                return bundle("rewrite", List.of(item("unexpected")));
            });
            QueryTransformationResult result = new QueryTransformationService(
                    new SingleRewritePolicyV1(), scenario.client()).apply(REQUEST, original, retrieval);

            assertThat(result.execution().status()).isEqualTo(scenario.status());
            assertThat(result.execution().retrievalInputCount()).isEqualTo(1);
            assertThat(result.evidence()).isSameAs(original);
            assertThat(retrievalCalls).hasValue(0);
        }
    }

    @Test
    void nonApplicableShapeDoesNotCallProvider() {
        AtomicInteger providerCalls = new AtomicInteger();
        QueryRewriteClient provider = (query, tokens) -> {
            providerCalls.incrementAndGet();
            return "rewrite";
        };
        RetrievalRequest request = RetrievalRequest.defaults("busy_timeout", RetrievalMode.HYBRID_VECTOR);
        EvidenceBundle original = bundle("busy_timeout", List.of(item("original")));

        QueryTransformationResult result = new QueryTransformationService(
                new SingleRewritePolicyV1(), provider).apply(request, original,
                mock(RetrievalService.class));

        assertThat(result.execution().status())
                .isEqualTo(QueryTransformationStatus.NO_OP_NOT_APPLICABLE);
        assertThat(result.execution().providerUsageStatus())
                .isEqualTo(ProviderUsageStatus.NOT_ATTEMPTED);
        assertThat(providerCalls).hasValue(0);
        assertThat(result.execution().applicability())
                .isEqualTo(QueryTransformationApplicability.QUERY_SHAPE_UNSUPPORTED);
    }

    @Test
    void applicabilityIsLimitedToLexicalMissCrowdOutWithOtherEvidence() {
        AtomicInteger providerCalls = new AtomicInteger();
        QueryRewriteClient provider = (query, tokens) -> {
            providerCalls.incrementAndGet();
            return "資料庫 busy_timeout";
        };
        QueryTransformationService service = new QueryTransformationService(
                new SingleRewritePolicyV1(), provider);

        QueryTransformationResult contributed = service.apply(REQUEST,
                bundle("original", List.of(item("original")), ModalityOutcome.CONTRIBUTED),
                mock(RetrievalService.class));
        QueryTransformationResult emptyOverall = service.apply(REQUEST,
                bundle("original", List.of(), ModalityOutcome.EMPTY),
                mock(RetrievalService.class));

        assertThat(contributed.execution().applicability())
                .isEqualTo(QueryTransformationApplicability.RETRIEVAL_SHAPE_UNSUPPORTED);
        assertThat(emptyOverall.execution().applicability())
                .isEqualTo(QueryTransformationApplicability.RETRIEVAL_SHAPE_UNSUPPORTED);
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void rewrittenRetrievalFailuresAndInvalidWorkspaceFallBackToOriginal() {
        EvidenceBundle original = bundle("original", List.of(item("original")));
        QueryTransformationService service = new QueryTransformationService(
                new SingleRewritePolicyV1(), (query, tokens) -> "資料庫 busy_timeout");

        QueryTransformationResult unavailable = service.apply(REQUEST, original,
                request -> { throw new org.km.llmwiki.rag.RetrievalUnavailableException(
                        org.km.llmwiki.rag.RetrievalUnavailableException.Dependency.SEARCH_INDEX,
                        new IllegalStateException("offline")); });
        EvidenceBundle foreign = new EvidenceBundle("rewrite", RetrievalMode.HYBRID_VECTOR,
                new EvidenceWorkspace(8, "other"), List.of(),
                new EvidenceBudget(8, 12_000, 0, 0, 0, false), 0, 0, true,
                RetrievalDiagnostics.hybrid().withLexicalOutcome(ModalityOutcome.EMPTY));
        QueryTransformationResult invalid = service.apply(REQUEST, original, request -> foreign);
        QueryTransformationResult nullResult = service.apply(REQUEST, original, request -> null);

        assertThat(unavailable.execution().status())
                .isEqualTo(QueryTransformationStatus.FALLBACK_RETRIEVAL_UNAVAILABLE);
        assertThat(unavailable.execution().retrievalInputCount()).isEqualTo(2);
        assertThat(invalid.execution().status())
                .isEqualTo(QueryTransformationStatus.FALLBACK_RETRIEVAL_INVALID);
        assertThat(nullResult.execution().status())
                .isEqualTo(QueryTransformationStatus.FALLBACK_RETRIEVAL_INVALID);
        assertThat(unavailable.evidence()).isSameAs(original);
        assertThat(invalid.evidence()).isSameAs(original);
    }

    @Test
    void mergePreservesOriginalAuthorityDeduplicatesAndEnforcesBudgets() {
        EvidenceItem originalItem = item("same");
        EvidenceItem rewrittenDuplicate = new EvidenceItem(EvidenceKind.WIKI, "same", WORKSPACE,
                99.0, "modified", "modified", false, "other-hash", "same", "other",
                "NOTE", "vault/other.md", 99, null, null, null, null, null, null, null);
        EvidenceItem second = item("second");
        EvidenceBundle original = bundleWithBudget("original", List.of(originalItem), 2, 40);
        EvidenceBundle rewritten = bundleWithBudget("rewrite",
                List.of(rewrittenDuplicate, second, item("excluded")), 8, 12_000);

        QueryTransformationResult result = new QueryTransformationService(
                new SingleRewritePolicyV1(), (query, tokens) -> "資料庫 busy_timeout")
                .apply(REQUEST, original, request -> rewritten);

        assertThat(result.evidence().items()).containsExactly(originalItem, second);
        assertThat(result.evidence().items().get(0)).isSameAs(originalItem);
        assertThat(result.evidence().budget().usedItems()).isEqualTo(2);
        assertThat(result.evidence().budget().usedCharacters()).isLessThanOrEqualTo(40);
        assertThat(result.evidence().budget().truncated()).isTrue();
        assertThat(result.evidence().items().get(0).contentHash()).isEqualTo("hash-same");
    }

    @Test
    void duplicateOnlyDoesNotInventTruncation() {
        EvidenceItem originalItem = item("same");
        EvidenceBundle original = bundleWithBudget("original", List.of(originalItem), 1, 100);
        EvidenceBundle rewritten = bundleWithBudget("rewrite", List.of(item("same")), 8, 12_000);

        QueryTransformationResult result = new QueryTransformationService(
                new SingleRewritePolicyV1(), (query, tokens) -> "資料庫 busy_timeout")
                .apply(REQUEST, original, request -> rewritten);

        assertThat(result.evidence().items()).containsExactly(originalItem);
        assertThat(result.evidence().budget().truncated()).isFalse();
    }

    @Test
    void rewrittenDuplicateCannotPreemptLaterOriginalAuthority() {
        EvidenceItem firstOriginal = item("first");
        EvidenceItem authoritative = item("same");
        EvidenceItem rewrittenDuplicate = new EvidenceItem(EvidenceKind.WIKI, "same", WORKSPACE,
                99.0, "modified", "modified", false, "other-hash", "same", "other",
                "NOTE", "vault/other.md", 99, null, null, null, null, null, null, null);
        EvidenceBundle original = bundleWithBudget("original",
                List.of(firstOriginal, authoritative), 3, 100);
        EvidenceBundle rewritten = bundleWithBudget("rewrite",
                List.of(rewrittenDuplicate, item("new")), 8, 12_000);

        QueryTransformationResult result = new QueryTransformationService(
                new SingleRewritePolicyV1(), (query, tokens) -> "資料庫 busy_timeout")
                .apply(REQUEST, original, request -> rewritten);

        assertThat(result.evidence().items())
                .containsExactly(firstOriginal, authoritative, item("new"));
        assertThat(result.evidence().items().get(1)).isSameAs(authoritative);
        assertThat(result.evidence().items().get(1).contentHash()).isEqualTo("hash-same");
    }

    @Test
    void providerUsageIsAvailableOnlyWhenCountersExist() {
        QueryRewriteClient withUsage = new QueryRewriteClient() {
            @Override public String rewrite(String query, List<String> tokens) { return "unused"; }
            @Override public QueryRewriteResult rewriteWithMetadata(String query, List<String> tokens) {
                return new QueryRewriteResult("資料庫 busy_timeout",
                        java.util.Optional.of(new AnswerUsageMetadata(10, 3, 13)));
            }
        };
        QueryTransformationResult available = new QueryTransformationService(
                new SingleRewritePolicyV1(), withUsage).apply(REQUEST,
                bundle("original", List.of(item("original"))),
                request -> bundle("rewrite", List.of(item("target"))));
        QueryTransformationResult unavailable = new QueryTransformationService(
                new SingleRewritePolicyV1(), (query, tokens) -> "資料庫 busy_timeout")
                .apply(REQUEST, bundle("original", List.of(item("original"))),
                        request -> bundle("rewrite", List.of(item("target"))));

        assertThat(available.execution().providerUsageStatus()).isEqualTo(ProviderUsageStatus.AVAILABLE);
        assertThat(available.execution().providerInputTokens()).isEqualTo(10);
        assertThat(available.execution().providerTotalTokens()).isEqualTo(13);
        assertThat(unavailable.execution().providerUsageStatus()).isEqualTo(ProviderUsageStatus.UNAVAILABLE);
        assertThat(unavailable.execution().providerInputTokens()).isNull();
    }

    @Test
    void outputValidationRejectsControlsNfcDuplicatesAndProjectedTermOverflow() {
        EvidenceBundle original = bundle("original", List.of(item("original")));
        QueryTransformationStatus control = applyRewrite(original, "資料庫\u0000 busy_timeout");
        RetrievalRequest accented = RetrievalRequest.defaults(
                "cafe\u0301 怎麼設定 busy_timeout", RetrievalMode.HYBRID_VECTOR);
        QueryTransformationStatus duplicate = new QueryTransformationService(
                new SingleRewritePolicyV1(), (query, tokens) -> "caf\u00e9 怎麼設定 busy_timeout")
                .apply(accented, original, request -> bundle("rewrite", List.of(item("x"))))
                .execution().status();
        String manyTerms = java.util.stream.IntStream.range(0, 70)
                .mapToObj(i -> "term" + i).collect(java.util.stream.Collectors.joining(" "))
                + " busy_timeout";

        assertThat(control).isEqualTo(QueryTransformationStatus.FALLBACK_PROVIDER_INVALID);
        assertThat(duplicate).isEqualTo(QueryTransformationStatus.NO_OP_DUPLICATE);
        assertThat(applyRewrite(original, manyTerms))
                .isEqualTo(QueryTransformationStatus.FALLBACK_OUTPUT_OVER_LIMIT);
    }

    @Test
    void exactTokenClassifierCoversPropertiesErrorsClassesAndPackages() {
        assertThat(QueryTransformationService.protectedTokens(
                "busy_timeout ORA-12899 NoSuchMethodError jakarta.persistence"))
                .containsExactly("busy_timeout", "ORA-12899", "NoSuchMethodError",
                        "jakarta.persistence");

        RetrievalRequest errorCodeRequest = RetrievalRequest.defaults(
                "ORA-12899 要怎麼處理資料欄位設定？", RetrievalMode.HYBRID_VECTOR);
        QueryTransformationResult caseChanged = new QueryTransformationService(
                new SingleRewritePolicyV1(), (query, tokens) -> "ora-12899 資料欄位")
                .apply(errorCodeRequest, bundle("original", List.of(item("original"))),
                        request -> bundle("rewrite", List.of(item("unexpected"))));

        assertThat(caseChanged.execution().status())
                .isEqualTo(QueryTransformationStatus.FALLBACK_EXACT_TOKEN_LOSS);
        assertThat(caseChanged.execution().retrievalInputCount()).isEqualTo(1);
        assertThat(caseChanged.evidence().items())
                .extracting(EvidenceItem::stableIdentity).containsExactly("WIKI:original");
    }

    @Test
    void executionMetadataRejectsImpossibleProviderMeasurements() {
        assertThatThrownBy(() -> new QueryTransformationExecution(
                "query-transform-single-rewrite-v1", QueryTransformationStatus.REWRITE_APPLIED,
                QueryTransformationApplicability.LEXICAL_MISS_CROWD_OUT,
                ProviderUsageStatus.AVAILABLE, 2, 1L, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueryTransformationExecution(
                "query-transform-single-rewrite-v1", QueryTransformationStatus.REWRITE_APPLIED,
                QueryTransformationApplicability.LEXICAL_MISS_CROWD_OUT,
                ProviderUsageStatus.AVAILABLE, 2, -1L, 1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueryTransformationExecution(
                "query-transform-single-rewrite-v1", QueryTransformationStatus.REWRITE_APPLIED,
                QueryTransformationApplicability.LEXICAL_MISS_CROWD_OUT,
                ProviderUsageStatus.AVAILABLE, 2, 1L, -1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueryTransformationExecution(
                "query-transform-single-rewrite-v1", QueryTransformationStatus.REWRITE_APPLIED,
                QueryTransformationApplicability.LEXICAL_MISS_CROWD_OUT,
                ProviderUsageStatus.UNAVAILABLE, 2, 1L, 1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static EvidenceBundle bundle(String query, List<EvidenceItem> items) {
        return bundle(query, items, ModalityOutcome.EMPTY);
    }

    private static EvidenceBundle bundle(String query, List<EvidenceItem> items,
                                         ModalityOutcome lexicalOutcome) {
        int characters = items.stream().mapToInt(item -> item.content().length()).sum();
        return new EvidenceBundle(query, RetrievalMode.HYBRID_VECTOR, WORKSPACE, items,
                new EvidenceBudget(8, 12_000, items.size(), characters,
                        (characters + 3) / 4, false), items.size(), 0, items.isEmpty(),
                RetrievalDiagnostics.hybrid().withLexicalOutcome(lexicalOutcome));
    }

    private static EvidenceBundle bundleWithBudget(String query, List<EvidenceItem> items,
                                                   int maxItems, int maxCharacters) {
        int characters = items.stream().mapToInt(item -> item.content().length()).sum();
        return new EvidenceBundle(query, RetrievalMode.HYBRID_VECTOR, WORKSPACE, items,
                new EvidenceBudget(maxItems, maxCharacters, items.size(), characters,
                        (characters + 3) / 4, false), items.size(), 0, items.isEmpty(),
                RetrievalDiagnostics.hybrid().withLexicalOutcome(ModalityOutcome.EMPTY));
    }

    private static QueryTransformationStatus applyRewrite(EvidenceBundle original,
                                                          String rewrite) {
        return new QueryTransformationService(new SingleRewritePolicyV1(),
                (query, tokens) -> rewrite).apply(REQUEST, original,
                request -> bundle("rewrite", List.of(item("target"))))
                .execution().status();
    }

    private static EvidenceItem item(String id) {
        return new EvidenceItem(EvidenceKind.WIKI, id, WORKSPACE, 1.0, "content " + id,
                "snippet", false, "hash-" + id, id, id, "NOTE", "vault/" + id + ".md",
                1, null, null, null, null, null, null, null);
    }

    private record Scenario(QueryRewriteClient client, QueryTransformationStatus status) { }
}
