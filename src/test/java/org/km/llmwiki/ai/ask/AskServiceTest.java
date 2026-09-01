package org.km.llmwiki.ai.ask;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.km.llmwiki.ai.answer.AnswerClient;
import org.km.llmwiki.ai.answer.AnswerContextAssembler;
import org.km.llmwiki.ai.answer.AnswerContextBudget;
import org.km.llmwiki.ai.answer.AnswerFailureType;
import org.km.llmwiki.ai.answer.AnswerGenerationOptions;
import org.km.llmwiki.ai.answer.AnswerProviderMetadata;
import org.km.llmwiki.ai.answer.AnswerRequest;
import org.km.llmwiki.ai.answer.AnswerResult;
import org.km.llmwiki.ai.answer.StubAnswerClient;
import org.km.llmwiki.rag.EvidenceBudget;
import org.km.llmwiki.rag.EvidenceBundle;
import org.km.llmwiki.rag.EvidenceItem;
import org.km.llmwiki.rag.EvidenceKind;
import org.km.llmwiki.rag.EvidenceWorkspace;
import org.km.llmwiki.rag.RetrievalMode;
import org.km.llmwiki.rag.RetrievalService;
import org.km.llmwiki.rag.RetrievalUnavailableException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
class AskServiceTest {

    private static final EvidenceWorkspace WORKSPACE = new EvidenceWorkspace(7, "Personal Wiki");
    private static final AnswerProviderMetadata METADATA =
            new AnswerProviderMetadata("stub", "offline-model");

    @Test
    void answersWikiSourceAndMixedEvidenceWithAuthoritativeCitations() {
        EvidenceBundle bundle = bundle(List.of(
                wiki("architecture", "Architecture", "vault/architecture.md", "Wiki fact"),
                source(41L, 900L, "design.pdf", "Source fact")));
        AnswerResult generated = new AnswerResult("Grounded answer", List.of("E2", "E1", "E2"),
                false, METADATA, Optional.empty());
        RetrievalService retrieval = retrievalReturning(bundle);

        AskResult result = new AskService(retrieval, new AnswerContextAssembler(),
                StubAnswerClient.returning(generated)).ask(
                        AskRequest.defaults("What is the design?", RetrievalMode.HYBRID_FTS));

        assertThat(result.successful()).isTrue();
        assertThat(result.answer()).contains("Grounded answer");
        assertThat(result.citations()).extracting(AskCitation::citationId)
                .containsExactly("E1", "E2");
        assertThat(result.citations().get(0).provenance()).isEqualTo(
                new org.km.llmwiki.ai.answer.AnswerContextProvenance.Wiki(
                        "Architecture", "vault/architecture.md", 1));
        assertThat(result.citations().get(1).provenance()).isEqualTo(
                new org.km.llmwiki.ai.answer.AnswerContextProvenance.Source(
                        "design.pdf", 900L, 41L, 1, 1, "Overview", "Root > Overview"));
        assertThat(result.suppliedEvidence()).extracting(AskCitation::citationId)
                .containsExactly("E1", "E2");
        assertThat(result.executionMetadata()).isEqualTo(new AskExecutionMetadata(2, 2, 20, false));
    }

    @Test
    void noEvidenceReturnsInsufficientEvidenceWithoutCallingProvider() {
        RetrievalService retrieval = retrievalReturning(bundle(List.of()));
        AtomicInteger calls = new AtomicInteger();
        AnswerClient provider = request -> {
            calls.incrementAndGet();
            throw new AssertionError("provider must not be called");
        };

        AskResult result = new AskService(retrieval, new AnswerContextAssembler(), provider)
                .ask(AskRequest.defaults("unknown", RetrievalMode.WIKI_ONLY));

        assertThat(result.status()).isEqualTo(AskStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.insufficientEvidence()).isTrue();
        assertThat(result.answer()).isEmpty();
        assertThat(result.failure()).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test
    void staleEvidenceRejectedByRetrievalCanStillProduceAnAnswerFromValidEvidence() {
        EvidenceBundle bundle = bundle(List.of(
                wiki("valid", "Valid", "vault/valid.md", "authoritative valid fact")));
        AtomicReference<AnswerRequest> captured = new AtomicReference<>();
        AnswerClient provider = request -> {
            captured.set(request);
            return new AnswerResult("Only the valid page is cited", List.of("E1"), false,
                    METADATA, Optional.empty());
        };

        AskResult result = new AskService(retrievalReturning(bundle), new AnswerContextAssembler(),
                provider).ask(AskRequest.defaults("mixed", RetrievalMode.WIKI_ONLY));

        assertThat(result.successful()).isTrue();
        assertThat(result.citations()).extracting(AskCitation::authorityIdentity)
                .containsExactly("WIKI:valid");
        assertThat(captured).hasValueSatisfying(request -> assertThat(request.context().blocks())
                .extracting(block -> block.authorityIdentity()).containsExactly("WIKI:valid"));
    }

    @Test
    void retrievalUnavailableIsTypedAndNeverConvertedToInsufficientEvidence() {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.retrieve(any())).thenThrow(new RetrievalUnavailableException(
                RetrievalUnavailableException.Dependency.SEARCH_INDEX,
                new IllegalStateException("database unavailable")));
        AnswerClient provider = request -> {
            throw new AssertionError("provider must not be called");
        };

        AskResult result = new AskService(retrieval, new AnswerContextAssembler(), provider)
                .ask(AskRequest.defaults("question", RetrievalMode.HYBRID_FTS));

        assertThat(result.status()).isEqualTo(AskStatus.FAILED);
        assertThat(result.insufficientEvidence()).isFalse();
        assertThat(result.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.type()).isEqualTo(AskFailureType.RETRIEVAL_UNAVAILABLE);
            assertThat(failure.retrievalDependency()).contains(
                    RetrievalUnavailableException.Dependency.SEARCH_INDEX);
        });
    }

    @ParameterizedTest
    @EnumSource(value = AnswerFailureType.class, names = {
            "CONFIGURATION_UNAVAILABLE_OR_DISABLED",
            "AUTHENTICATION_OR_AUTHORIZATION",
            "RATE_LIMIT_OR_QUOTA",
            "TIMEOUT_OR_NETWORK_UNAVAILABLE",
            "PROVIDER_SERVER_FAILURE",
            "INVALID_PROVIDER_RESPONSE"
    })
    void providerFailuresRemainTypedAndRetainSuppliedEvidence(AnswerFailureType providerFailure) {
        EvidenceBundle bundle = bundle(List.of(wiki("one", "One", "vault/one.md", "fact")));
        AskResult result = new AskService(retrievalReturning(bundle), new AnswerContextAssembler(),
                StubAnswerClient.failing(providerFailure, "authorization: Bearer secret"))
                .ask(AskRequest.defaults("question", RetrievalMode.WIKI_ONLY));

        assertThat(result.status()).isEqualTo(AskStatus.FAILED);
        assertThat(result.answer()).isEmpty();
        assertThat(result.suppliedEvidence()).extracting(AskCitation::citationId)
                .containsExactly("E1");
        assertThat(result.failure()).hasValueSatisfying(failure -> {
            assertThat(failure.type()).isNotEqualTo(AskFailureType.RETRIEVAL_UNAVAILABLE);
            assertThat(failure.diagnostic()).doesNotContain("secret");
        });
    }

    @Test
    void hallucinatedCitationIsAnInvalidGenerationFailure() {
        EvidenceBundle bundle = bundle(List.of(wiki("one", "One", "vault/one.md", "fact")));
        AskResult result = new AskService(retrievalReturning(bundle), new AnswerContextAssembler(),
                StubAnswerClient.returning(new AnswerResult("hallucinated", List.of("E99"), false,
                        METADATA, Optional.empty())))
                .ask(AskRequest.defaults("question", RetrievalMode.WIKI_ONLY));

        assertThat(result.status()).isEqualTo(AskStatus.FAILED);
        assertThat(result.successful()).isFalse();
        assertThat(result.failure()).hasValueSatisfying(failure ->
                assertThat(failure.type()).isEqualTo(AskFailureType.PROVIDER_INVALID_RESPONSE));
        assertThat(result.suppliedEvidence()).hasSize(1);
    }

    @Test
    void contextBudgetIsAppliedBeforeProviderAndProducesAValidBoundedRequest() {
        EvidenceBundle bundle = bundle(List.of(
                wiki("one", "One", "vault/one.md", "A😀BC"),
                wiki("two", "Two", "vault/two.md", "second")));
        AtomicReference<AnswerRequest> captured = new AtomicReference<>();
        AnswerClient provider = request -> {
            captured.set(request);
            return new AnswerResult("bounded", List.of("E1"), false, METADATA,
                    Optional.empty());
        };

        AskResult result = new AskService(retrievalReturning(bundle), new AnswerContextAssembler(), provider)
                .ask(new AskRequest("question", RetrievalMode.WIKI_ONLY, 8, 100,
                        new AnswerContextBudget(8, 3, 5), new AnswerGenerationOptions(20)));

        assertThat(result.successful()).isTrue();
        assertThat(captured).hasValueSatisfying(request -> {
            assertThat(request.context().usage().usedCodePoints()).isEqualTo(5);
            assertThat(request.context().usage().truncated()).isTrue();
            assertThat(request.options().maxOutputCharacters()).isEqualTo(20);
        });
    }

    @Test
    void deterministicStubProducesTheSameAskResultForTheSameInput() {
        EvidenceBundle bundle = bundle(List.of(wiki("one", "One", "vault/one.md", "fact")));
        AskService service = new AskService(retrievalReturning(bundle), new AnswerContextAssembler(),
                StubAnswerClient.returning(new AnswerResult("deterministic", List.of("E1"), false,
                        METADATA, Optional.empty())));

        AskResult first = service.ask(AskRequest.defaults("question", RetrievalMode.WIKI_ONLY));
        AskResult second = service.ask(AskRequest.defaults("question", RetrievalMode.WIKI_ONLY));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void askRequestRejectsUnboundedRetrievalAndGenerationSettings() {
        assertThatThrownBy(() -> new AskRequest("question", RetrievalMode.WIKI_ONLY,
                AskRequest.MAX_RETRIEVAL_ITEMS + 1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AskRequest("question", RetrievalMode.WIKI_ONLY,
                null, AskRequest.MAX_RETRIEVAL_CHARACTERS + 1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RetrievalService retrievalReturning(EvidenceBundle bundle) {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.retrieve(any())).thenReturn(bundle);
        return retrieval;
    }

    private static EvidenceBundle bundle(List<EvidenceItem> items) {
        int characters = items.stream().mapToInt(item ->
                item.content().codePointCount(0, item.content().length())).sum();
        return new EvidenceBundle("question", RetrievalMode.HYBRID_FTS, WORKSPACE, items,
                new EvidenceBudget(8, 100_000, items.size(), characters, (characters + 3) / 4,
                        false), items.size(), 0, items.isEmpty());
    }

    private static EvidenceItem wiki(String id, String title, String path, String content) {
        return new EvidenceItem(EvidenceKind.WIKI, id, WORKSPACE, 0.9, content, "snippet", false,
                "hash-" + id, id, title, "CONCEPT", path, 1,
                null, null, null, null, null, null, null);
    }

    private static EvidenceItem source(long chunkId, long documentId, String documentName,
                                       String content) {
        return new EvidenceItem(EvidenceKind.SOURCE_CHUNK, Long.toString(chunkId), WORKSPACE, 0.8,
                content, "snippet", false, "hash-source-" + chunkId, null, null, null, null,
                null, chunkId, documentId, documentName, 1, 1, "Overview", "Root > Overview");
    }
}
