package org.km.llmwiki.ai.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.answer.AnswerUsageMetadata;
import org.km.llmwiki.ai.answer.ProviderUsageStatus;
import org.km.llmwiki.ai.provider.ProviderEndpointSecurityPolicy;
import org.km.llmwiki.ai.query.provider.openai.OpenAiCompatibleQueryRewriteClient;
import org.km.llmwiki.ai.query.provider.openai.OpenAiCompatibleQueryRewriteProperties;
import org.km.llmwiki.rag.EvidenceBudget;
import org.km.llmwiki.rag.EvidenceBundle;
import org.km.llmwiki.rag.EvidenceItem;
import org.km.llmwiki.rag.EvidenceKind;
import org.km.llmwiki.rag.EvidenceWorkspace;
import org.km.llmwiki.rag.ModalityOutcome;
import org.km.llmwiki.rag.RetrievalDiagnostics;
import org.km.llmwiki.rag.RetrievalMode;
import org.km.llmwiki.rag.RetrievalRequest;
import org.km.llmwiki.web.DiagnosticRedaction;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Versioned live-provider controlled measurement contract for issue #408
 * ({@code query-rewrite-live-measurement-v1}).
 *
 * <p>This suite never touches the network, never requires a provider credential, and never
 * asserts a live quality gain. It locks the procedure's executable boundaries so a future
 * controlled run cannot silently fabricate evidence:
 *
 * <ul>
 *   <li>procedure version identifier shape;</li>
 *   <li>{@code UNAVAILABLE} token/latency semantics (absent counters stay {@code null},
 *       never {@code 0});</li>
 *   <li>disabled/unconfigured provider fails closed without egress;</li>
 *   <li>exact-token loss stays a blocking gate (no second retrieval);</li>
 *   <li>live report fields stay operator-safe (no key/endpoint/path/RID/raw payload).</li>
 * </ul>
 */
@Tag("unit")
class QueryRewriteLiveMeasurementProcedureTest {

    static final String PROCEDURE_VERSION = "query-rewrite-live-measurement-v1";

    private static final EvidenceWorkspace WORKSPACE = new EvidenceWorkspace(7, "wiki");
    private static final RetrievalRequest REQUEST = RetrievalRequest.defaults(
            "資料庫連線的 busy_timeout 預設值要怎麼設定？", RetrievalMode.HYBRID_VECTOR);

    @Test
    void procedureVersionIsVersionedAndStable() {
        assertThat(PROCEDURE_VERSION)
                .matches("[a-z0-9][a-z0-9._-]*")
                .isEqualTo("query-rewrite-live-measurement-v1");
    }

    @Test
    void unavailableUsageNeverFabricatesZeroCounters() {
        QueryTransformationService service = new QueryTransformationService(
                new SingleRewritePolicyV1(), (query, tokens) -> "資料庫 busy_timeout");
        QueryTransformationResult result = service.apply(REQUEST,
                bundle(List.of(item("original"))),
                request -> bundle(List.of(item("target"))));

        // Fixture client reports no usage counters: the execution must stay UNAVAILABLE with
        // null counters, never zero-filled.
        assertThat(result.execution().providerUsageStatus())
                .isEqualTo(ProviderUsageStatus.UNAVAILABLE);
        assertThat(result.execution().providerInputTokens()).isNull();
        assertThat(result.execution().providerOutputTokens()).isNull();
        assertThat(result.execution().providerTotalTokens()).isNull();
    }

    @Test
    void availableUsageRequiresAtLeastOneCounter() {
        QueryRewriteClient withUsage = new QueryRewriteClient() {
            @Override
            public String rewrite(String query, List<String> tokens) {
                return "unused";
            }

            @Override
            public QueryRewriteResult rewriteWithMetadata(String query, List<String> tokens) {
                return new QueryRewriteResult("資料庫 busy_timeout",
                        Optional.of(new AnswerUsageMetadata(12, 4, 16)));
            }
        };
        QueryTransformationResult result = new QueryTransformationService(
                new SingleRewritePolicyV1(), withUsage).apply(REQUEST,
                bundle(List.of(item("original"))),
                request -> bundle(List.of(item("target"))));

        assertThat(result.execution().providerUsageStatus())
                .isEqualTo(ProviderUsageStatus.AVAILABLE);
        assertThat(result.execution().providerInputTokens()).isEqualTo(12);
        assertThat(result.execution().providerOutputTokens()).isEqualTo(4);
        assertThat(result.execution().providerTotalTokens()).isEqualTo(16);
    }

    @Test
    void disabledProviderFailsClosedWithoutEgress() {
        OpenAiCompatibleQueryRewriteProperties properties =
                new OpenAiCompatibleQueryRewriteProperties();
        properties.setEnabled(false);
        OpenAiCompatibleQueryRewriteClient client =
                new OpenAiCompatibleQueryRewriteClient(properties, new ObjectMapper());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> client.rewriteWithMetadata("資料庫 busy_timeout", List.of()))
                .isInstanceOf(QueryRewriteException.class)
                .matches(failure -> ((QueryRewriteException) failure).type()
                        == QueryRewriteException.Type.UNAVAILABLE);

        QueryTransformationService service = new QueryTransformationService(
                new SingleRewritePolicyV1(), client);
        QueryTransformationResult result = service.apply(REQUEST,
                bundle(List.of(item("original"))), mock(org.km.llmwiki.rag.RetrievalService.class));

        assertThat(result.execution().status())
                .isEqualTo(QueryTransformationStatus.FALLBACK_PROVIDER_UNAVAILABLE);
        assertThat(result.execution().providerUsageStatus())
                .isEqualTo(ProviderUsageStatus.UNAVAILABLE);
        assertThat(result.execution().retrievalInputCount()).isEqualTo(1);
    }

    @Test
    void exactTokenLossRemainsBlockingInAnyLiveRun() {
        java.util.concurrent.atomic.AtomicInteger retrievals = new java.util.concurrent.atomic.AtomicInteger();
        QueryTransformationService service = new QueryTransformationService(
                new SingleRewritePolicyV1(), (query, tokens) -> {
                    assertThat(tokens).contains("busy_timeout");
                    return "資料庫連線逾時";
                });
        QueryTransformationResult result = service.apply(REQUEST,
                bundle(List.of(item("original"))),
                request -> {
                    retrievals.incrementAndGet();
                    return bundle(List.of(item("unexpected")));
                });

        assertThat(result.execution().status())
                .isEqualTo(QueryTransformationStatus.FALLBACK_EXACT_TOKEN_LOSS);
        assertThat(result.execution().retrievalInputCount()).isEqualTo(1);
        assertThat(retrievals).hasValue(0);
        assertThat(result.evidence().items()).extracting(EvidenceItem::stableIdentity)
                .containsExactly("WIKI:original");
    }

    @Test
    void liveReportFieldsStayOperatorSafe() {
        String hostile = "key=sk-live-abc123 path=/vault/secret.md RID=#12:34 "
                + "SELECT * FROM knowledge_page Authorization: Bearer token";
        String redacted = DiagnosticRedaction.sanitize(hostile, "query rewrite failed", 160);

        assertThat(redacted).doesNotContain("sk-live-abc123");
        assertThat(redacted).doesNotContain("/vault/secret.md");
        assertThat(redacted).doesNotContain("#12:34");
        assertThat(redacted).doesNotContain("Bearer");
        assertThat(redacted).doesNotContain("SELECT * FROM");

        // Destination classification and transport policy must agree for the rewrite purpose:
        // loopback stays local, remote https stays secure, unconfigured stays unavailable.
        assertThat(ProviderEndpointSecurityPolicy
                .classify("http://127.0.0.1:8765/v1", false).name())
                .isEqualTo("LOCAL_LOOPBACK");
        assertThat(ProviderEndpointSecurityPolicy
                .classify("https://api.openai.com/v1", false).name())
                .isEqualTo("REMOTE_SECURE");
    }

    private static EvidenceBundle bundle(List<EvidenceItem> items) {
        int characters = items.stream().mapToInt(item -> item.content().length()).sum();
        return new EvidenceBundle(REQUEST.query(), RetrievalMode.HYBRID_VECTOR, WORKSPACE, items,
                new EvidenceBudget(8, 12_000, items.size(), characters,
                        (characters + 3) / 4, false),
                items.size(), 0, items.isEmpty(),
                RetrievalDiagnostics.hybrid().withLexicalOutcome(ModalityOutcome.EMPTY));
    }

    private static EvidenceItem item(String id) {
        return new EvidenceItem(EvidenceKind.WIKI, id, WORKSPACE, 1.0, "content " + id,
                "snippet", false, "hash-" + id, id, id, "NOTE", "vault/" + id + ".md",
                1, null, null, null, null, null, null, null);
    }
}
