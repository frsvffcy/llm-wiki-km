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
import org.km.llmwiki.rag.RetrievalMode;

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
}
