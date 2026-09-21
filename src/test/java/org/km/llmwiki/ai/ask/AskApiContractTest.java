package org.km.llmwiki.ai.ask;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.km.llmwiki.ai.answer.AnswerContextDiagnostics;
import org.km.llmwiki.ai.answer.AnswerUsageMetadata;
import org.km.llmwiki.ai.answer.ContextProjectionFailureType;
import org.km.llmwiki.ai.answer.ProjectionKind;
import org.km.llmwiki.ai.answer.ProviderUsageStatus;
import org.km.llmwiki.ai.query.QueryTransformationApplicability;
import org.km.llmwiki.ai.query.QueryTransformationExecution;
import org.km.llmwiki.ai.query.QueryTransformationStatus;
import org.km.llmwiki.rag.RerankNoOpReason;
import org.km.llmwiki.rag.RerankStatus;
import org.km.llmwiki.rag.RetrievalDiagnostics;
import org.km.llmwiki.rag.RetrievalMode;
import org.km.llmwiki.rag.RetrievalRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("contract")
class AskApiContractTest {

    @Test
    void requestBoundsQuestionByUnicodeCodePoints() {
        AskApiRequest request = new AskApiRequest("  中文😀  ", RetrievalMode.HYBRID_FTS);

        assertThat(request.question()).isEqualTo("中文😀");
        assertThat(request.toApplicationRequest().retrievalMode()).isEqualTo(RetrievalMode.HYBRID_FTS);
        assertThat(request.toApplicationRequest().question()).isEqualTo("中文😀");
    }

    @Test
    void documentScopeUsesOnlyAPositiveApplicationOwnedDocumentId() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var scoped = AskApiRequest.fromJson(mapper.valueToTree(Map.of(
                "question", "只問這份文件",
                "retrievalMode", "HYBRID_GRAPH",
                "documentId", 42)));

        assertThat(scoped.documentId()).isEqualTo(42L);
        assertThat(scoped.toApplicationRequest().documentScope().documentId()).isEqualTo(42L);
        assertThat(scoped.toApplicationRequest().retrievalRequest().corpus())
                .isEqualTo(org.km.llmwiki.search.SearchCorpus.SOURCE);

        for (Object invalid : List.of(0, -1, 1.5, "42")) {
            var body = mapper.valueToTree(Map.of(
                    "question", "q", "retrievalMode", "HYBRID_FTS", "documentId", invalid));
            assertThatThrownBy(() -> AskApiRequest.fromJson(body))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("documentId");
        }
    }

    @Test
    void scopedModeMatrixKeepsModeStrategyAndMakesSourceCorpusExplicit() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (RetrievalMode mode : RetrievalMode.values()) {
            var body = mapper.valueToTree(Map.of(
                    "question", "q", "retrievalMode", mode.name(), "documentId", 42));
            RetrievalRequest request = AskApiRequest.fromJson(body)
                    .toApplicationRequest().retrievalRequest();

            assertThat(request.mode()).isEqualTo(mode);
            assertThat(request.corpus())
                    .as("resolved corpus for %s", mode)
                    .isEqualTo(org.km.llmwiki.search.SearchCorpus.SOURCE);
            assertThat(request.resolvedCorpus()).isEqualTo(
                    org.km.llmwiki.search.SearchCorpus.SOURCE);
            assertThat(request.resolvedStrategy()).isEqualTo(mode.strategy());
            assertThat(request.documentScoped()).isTrue();

            RetrievalDiagnostics diagnostics = switch (mode.strategy()) {
                case LEXICAL -> RetrievalDiagnostics.lexical();
                case SEMANTIC -> RetrievalDiagnostics.semantic();
                case HYBRID -> RetrievalDiagnostics.hybrid();
                case FUSED -> RetrievalDiagnostics.fused();
            };
            AskApiResponse.RetrievalMetadata metadata =
                    AskApiResponse.RetrievalMetadata.from(diagnostics.withRequest(request));
            AskApiResponse response = new AskApiResponse(AskStatus.INSUFFICIENT_EVIDENCE,
                    null, true, List.of(), null, null, metadata);
            var responseJson = mapper.valueToTree(response).get("retrievalMetadata");
            assertThat(responseJson.get("requestedMode").asText()).isEqualTo(mode.name());
            assertThat(responseJson.get("resolvedCorpus").asText()).isEqualTo("SOURCE");
            assertThat(responseJson.get("documentScoped").asBoolean()).isTrue();
            assertThat(responseJson.get("strategy").asText())
                    .isEqualTo(mode.strategy().name());
        }
    }

    @Test
    void omittedDocumentScopePreservesTheExistingUnscopedAskContract() {
        var body = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(Map.of(
                "question", "整個知識庫", "retrievalMode", "HYBRID_FTS"));

        AskRequest request = AskApiRequest.fromJson(body).toApplicationRequest();

        assertThat(request.documentScope()).isNull();
        assertThat(request.retrievalRequest().documentScope()).isNull();
        assertThat(request.retrievalRequest().corpus())
                .isEqualTo(org.km.llmwiki.search.SearchCorpus.ALL);

        RetrievalDiagnostics diagnostics = RetrievalDiagnostics.lexical();
        assertThat(diagnostics.withRequest(request.retrievalRequest())).isSameAs(diagnostics);
        AskApiResponse.RetrievalMetadata metadata =
                AskApiResponse.RetrievalMetadata.from(diagnostics);
        AskApiResponse response = new AskApiResponse(AskStatus.INSUFFICIENT_EVIDENCE,
                null, true, List.of(), null, null, metadata);
        var metadataJson = new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(response).get("retrievalMetadata");
        assertThat(metadataJson.has("requestedMode")).isFalse();
        assertThat(metadataJson.has("resolvedCorpus")).isFalse();
        assertThat(metadataJson.has("documentScoped")).isFalse();
    }

    @Test
    void rejectsBlankAndOversizedUnicodeQuestion() {
        assertThatThrownBy(() -> new AskApiRequest(" \t", RetrievalMode.WIKI_ONLY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AskApiRequest("😀".repeat(4_001), RetrievalMode.WIKI_ONLY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unicode code points");
    }

    @Test
    void responseProjectionOmitsEvidenceContentHashesAndUnsafeWikiPaths() {
        AskCitation citation = new AskCitation("E1", org.km.llmwiki.rag.EvidenceKind.WIKI,
                "WIKI:secret", "hash-secret",
                new org.km.llmwiki.ai.answer.AnswerContextProvenance.Wiki(
                        "Security", "/Users/private/vault/security.md", 3));
        AskResult result = new AskResult(AskStatus.ANSWERED,
                java.util.Optional.of("safe answer"), java.util.List.of(citation),
                java.util.List.of(citation), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), new AskExecutionMetadata(1, 1, 4, false));

        AskApiResponse response = AskApiResponse.from(result);

        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().getFirst().provenance().path()).isNull();
        assertThat(response.citations().getFirst().provenance().title()).isEqualTo("Security");
    }

    @Test
    void responseProjectionExposesBoundedDiagnosticsWithoutProviderOrBackendDetails() {
        AnswerContextDiagnostics diagnostics = new AnswerContextDiagnostics(
                4, 2, 2, 100, 80, 50, 0.5d, true, true,
                "RID:secret-token",
                Map.of(ProjectionKind.VERBATIM, 1, ProjectionKind.EXTRACTIVE, 0,
                        ProjectionKind.TRUNCATED, 1, ProjectionKind.NO_OP, 0),
                true, ContextProjectionFailureType.PROJECTION_LIMIT_EXCEEDED,
                12L, 34L, ProviderUsageStatus.AVAILABLE,
                17, null, 22);
        AskCitation citation = new AskCitation("E1", org.km.llmwiki.rag.EvidenceKind.WIKI,
                "WIKI:secret", "hash-secret",
                new org.km.llmwiki.ai.answer.AnswerContextProvenance.Wiki(
                        "Security", "vault/security.md", 3));
        AskResult result = new AskResult(AskStatus.ANSWERED,
                Optional.of("safe answer"), List.of(citation), List.of(citation),
                Optional.empty(), Optional.of(new AnswerUsageMetadata(17, null, 22)),
                Optional.empty(), AskExecutionMetadata.fromDiagnostics(diagnostics));

        AskApiResponse.ContextDiagnostics projected = AskApiResponse.from(result)
                .executionMetadata().contextDiagnostics();

        assertThat(projected.retrievedEvidenceCount()).isEqualTo(4);
        assertThat(projected.admittedEvidenceCount()).isEqualTo(2);
        assertThat(projected.answerContextBlockCount()).isEqualTo(2);
        assertThat(projected.originalCodePoints()).isEqualTo(100);
        assertThat(projected.packedCodePoints()).isEqualTo(80);
        assertThat(projected.projectedCodePoints()).isEqualTo(50);
        assertThat(projected.truncated()).isTrue();
        assertThat(projected.compacted()).isTrue();
        assertThat(projected.contextPolicyVersion()).isNull();
        assertThat(projected.providerUsageStatus()).isEqualTo(ProviderUsageStatus.AVAILABLE);
        assertThat(projected.providerInputTokens()).isEqualTo(17);
        assertThat(projected.providerOutputTokens()).isNull();
        assertThat(projected.providerTotalTokens()).isEqualTo(22);
        assertThat(AskApiResponse.from(result).toString())
                .doesNotContain("secret-token", "hash-secret", "WIKI:secret");
    }

    @Test
    void graphGroundedModeIsAcceptedAdditivelyWithoutChangingExistingModes() {
        AskApiRequest graphGrounded = new AskApiRequest("question", RetrievalMode.HYBRID_GRAPH);

        assertThat(graphGrounded.toApplicationRequest().retrievalMode())
                .isEqualTo(RetrievalMode.HYBRID_GRAPH);
        assertThat(graphGrounded.toApplicationRequest().retrievalRequest().strategy())
                .isEqualTo(org.km.llmwiki.rag.RetrievalStrategy.FUSED);

        // The six pre-existing modes keep their public semantics untouched.
        assertThat(RetrievalMode.values()).hasSize(7);
        assertThat(RetrievalMode.HYBRID_FTS.strategy())
                .isEqualTo(org.km.llmwiki.rag.RetrievalStrategy.LEXICAL);
        assertThat(RetrievalMode.HYBRID_VECTOR.strategy())
                .isEqualTo(org.km.llmwiki.rag.RetrievalStrategy.HYBRID);
    }

    @ParameterizedTest
    @EnumSource(RetrievalMode.class)
    void everyPublicModeSerializesThroughTheSameJsonBoundary(RetrievalMode mode) {
        com.fasterxml.jackson.databind.JsonNode body =
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(
                        java.util.Map.of("question", "q", "retrievalMode", mode.name()));

        assertThat(AskApiRequest.fromJson(body).toApplicationRequest().retrievalMode())
                .isEqualTo(mode);
    }

    @Test
    void omittedRetrievalModeIsRejectedWithoutAnyDefaultInjection() {
        // The request boundary owns no mode defaulting policy: omitting the mode is a client
        // error, never a silent switch to the graph-grounded or any other mode.
        com.fasterxml.jackson.databind.JsonNode body =
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(
                        java.util.Map.of("question", "q"));

        assertThatThrownBy(() -> AskApiRequest.fromJson(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retrievalMode");
    }

    @Test
    void rejectsUnknownRetrievalModesBeforeTheApplicationService() {
        com.fasterxml.jackson.databind.JsonNode body =
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(
                        java.util.Map.of("question", "q", "retrievalMode", "GRAPH_RAG"));

        assertThatThrownBy(() -> AskApiRequest.fromJson(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retrievalMode is invalid");
    }

    @Test
    void controllerOwnsNoRetrievalFusionOrGraphPolicy() {
        // The REST adapter stays thin: mode validation, DTO mapping, and typed error mapping
        // only. Seed selection, traversal, authority revalidation, fusion, currentness, and
        // citation identity remain in the application layer.
        assertThat(java.util.List.of(AskController.class.getDeclaredFields()))
                .allSatisfy(field -> {
                    assertThat(field.getType().getSimpleName()).doesNotContain("Fused");
                    assertThat(field.getType().getSimpleName()).doesNotContain("Graph");
                    assertThat(field.getType().getPackageName())
                            .doesNotStartWith("org.km.llmwiki.graph");
                });
    }

    @Test
    void answeredResultRequiresAtLeastOneCitation() {
        assertThatThrownBy(() -> new AskResult(AskStatus.ANSWERED,
                java.util.Optional.of("ungrounded answer"), java.util.List.of(),
                java.util.List.of(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), new AskExecutionMetadata(1, 1, 8, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one citation");
    }

    @Test
    void executionMetadataProjectsRerankOutcomeAsAdditiveTypedFields() {
        var metadata = new AskExecutionMetadata(0, 0, 0, false,
                AnswerContextDiagnostics.empty(), "rerank-policy-v1-exact-anchor",
                RerankStatus.APPLIED, null);

        var projected = AskApiResponse.from(new AskResult(AskStatus.ANSWERED,
                Optional.of("answer"),
                List.of(new AskCitation("E1",
                        org.km.llmwiki.rag.EvidenceKind.WIKI, "WIKI:wiki-x",
                        "hash-wiki-x",
                        new org.km.llmwiki.ai.answer.AnswerContextProvenance.Wiki(
                                "Wiki X", "vault/wiki-x.md", 1))),
                        List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                        metadata, RetrievalDiagnostics.lexical()));

        assertThat(projected.executionMetadata().rerankPolicyVersion())
                .isEqualTo("rerank-policy-v1-exact-anchor");
        assertThat(projected.executionMetadata().rerankStatus()).isEqualTo(RerankStatus.APPLIED);
        assertThat(projected.executionMetadata().rerankNoOpReason()).isNull();
        assertThat(projected.executionMetadata().contextDiagnostics().providerUsageStatus())
                .isEqualTo(ProviderUsageStatus.NOT_ATTEMPTED);
    }

    @Test
    void executionMetadataProjectsBoundedQueryTransformationOutcome() {
        QueryTransformationExecution transformation = new QueryTransformationExecution(
                "query-transform-single-rewrite-v1",
                QueryTransformationStatus.REWRITE_APPLIED,
                QueryTransformationApplicability.LEXICAL_MISS_CROWD_OUT,
                ProviderUsageStatus.AVAILABLE, 2, 41L, 12, 5, 17);
        var metadata = new AskExecutionMetadata(0, 0, 0, false,
                AnswerContextDiagnostics.empty(), "rerank-policy-v1-exact-anchor",
                RerankStatus.APPLIED, null, transformation);

        AskApiResponse.QueryTransformationMetadata projected = AskApiResponse.from(
                new AskResult(AskStatus.ANSWERED, Optional.of("answer"),
                        List.of(new AskCitation("E1",
                                org.km.llmwiki.rag.EvidenceKind.WIKI, "WIKI:wiki-x",
                                "hash-wiki-x",
                                new org.km.llmwiki.ai.answer.AnswerContextProvenance.Wiki(
                                        "Wiki X", "vault/wiki-x.md", 1))),
                        List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                        metadata, RetrievalDiagnostics.lexical()))
                .executionMetadata().queryTransformation();

        assertThat(projected.policyVersion()).isEqualTo("query-transform-single-rewrite-v1");
        assertThat(projected.status()).isEqualTo(QueryTransformationStatus.REWRITE_APPLIED);
        assertThat(projected.applicability())
                .isEqualTo(QueryTransformationApplicability.LEXICAL_MISS_CROWD_OUT);
        assertThat(projected.providerUsageStatus()).isEqualTo(ProviderUsageStatus.AVAILABLE);
        assertThat(projected.retrievalInputCount()).isEqualTo(2);
        assertThat(projected.providerLatencyMs()).isEqualTo(41L);
        assertThat(projected.providerInputTokens()).isEqualTo(12);
        assertThat(projected.providerOutputTokens()).isEqualTo(5);
        assertThat(projected.providerTotalTokens()).isEqualTo(17);
    }
}
