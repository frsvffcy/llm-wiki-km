package org.km.llmwiki.ai.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit contract for the shared Ask application boundary (#331): strict parsing, orchestration
 * delegation without added policy, safe projection, and typed failure mapping. Both transport
 * adapters delegate to these exact methods.
 */
@Tag("unit")
class AskApplicationServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parseRequestAppliesIdenticalStrictRulesForEveryAdapter() {
        AskApplicationService application = new AskApplicationService(mock(AskService.class));

        AskApiRequest parsed = application.parseRequest(
                JSON.createObjectNode().put("question", "  padded  ").put("retrievalMode",
                        "WIKI_ONLY"));

        assertThat(parsed.question()).isEqualTo("padded");
        assertThat(parsed.retrievalMode())
                .isEqualTo(org.km.llmwiki.rag.RetrievalMode.WIKI_ONLY);
        assertThatThrownBy(() -> application.parseRequest(
                JSON.createObjectNode().put("question", "q").put("retrievalMode", "NOPE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("retrievalMode is invalid");
    }

    @Test
    void executeDelegatesOrchestrationToAskServiceWithoutAddedPolicy() {
        AskService askService = mock(AskService.class);
        AskResult result = new AskResult(AskStatus.INSUFFICIENT_EVIDENCE, Optional.empty(),
                List.of(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                new AskExecutionMetadata(0, 0, 0, false),
                org.km.llmwiki.rag.RetrievalDiagnostics.lexical());
        when(askService.ask(any())).thenReturn(result);
        AskApplicationService application = new AskApplicationService(askService);

        assertThat(application.execute(new AskApiRequest("q",
                        org.km.llmwiki.rag.RetrievalMode.WIKI_ONLY)))
                .isSameAs(result);
        verify(askService).ask(any(AskRequest.class));
    }

    @Test
    void projectAndFailureShareOneMappingImplementation() {
        AskApplicationService application =
                new AskApplicationService(mock(AskService.class));
        AskCitation citation = new AskCitation("E1", org.km.llmwiki.rag.EvidenceKind.WIKI,
                "WIKI:architecture", "hash-wiki",
                new org.km.llmwiki.ai.answer.AnswerContextProvenance.Wiki(
                        "Architecture", "vault/architecture.md", 4));
        AskResult answered = new AskResult(AskStatus.ANSWERED, Optional.of("answer"),
                List.of(citation), List.of(citation), Optional.empty(), Optional.empty(),
                Optional.empty(),
                new AskExecutionMetadata(0, 0, 0, false),
                org.km.llmwiki.rag.RetrievalDiagnostics.lexical());

        assertThat(application.project(answered)).isEqualTo(AskApiResponse.from(answered));

        AskResult failed = new AskResult(AskStatus.FAILED, Optional.empty(), List.of(),
                List.of(), Optional.empty(), Optional.empty(),
                Optional.of(new AskFailure(AskFailureType.PROVIDER_TIMEOUT_OR_NETWORK_UNAVAILABLE,
                        "timed out")),
                new AskExecutionMetadata(0, 0, 0, false),
                org.km.llmwiki.rag.RetrievalDiagnostics.lexical());
        assertThatThrownBy(() -> application.failure(answered))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(application.failure(failed).failureType())
                .isEqualTo(AskFailureType.PROVIDER_TIMEOUT_OR_NETWORK_UNAVAILABLE);
    }

    @Test
    void jsonNodeShapeIsAcceptedButUnknownFieldsAreRejected() {
        AskApplicationService application = new AskApplicationService(mock(AskService.class));
        JsonNode body = JSON.createObjectNode().put("question", "q")
                .put("retrievalMode", "WIKI_ONLY").put("maxItems", 5);

        assertThatThrownBy(() -> application.parseRequest(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported Ask request field");
    }
}
