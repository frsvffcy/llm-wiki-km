package org.km.llmwiki.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.ask.AskApiException;
import org.km.llmwiki.ai.ask.AskFailureType;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailure;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.rag.RetrievalUnavailableException;
import org.km.llmwiki.source.DocumentExtractionException;
import org.km.llmwiki.source.DocumentNotFoundException;
import org.km.llmwiki.wiki.WikiDraftLifecycleException;
import org.km.llmwiki.wiki.WikiPublishException;
import org.km.llmwiki.workspace.DuplicateWorkspaceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the public REST error boundary: stable codes and HTTP statuses are
 * decided by the typed exception, and no exception class name, raw arbitrary message,
 * filesystem path, secret-like assignment, SQL fragment, or backend identity ever reaches the
 * response body. These tests actively falsify the redaction boundary with hostile messages.
 */
@Tag("contract")
class GlobalExceptionHandlerTest {

    private static final String HOSTILE =
            "root /Users/toddyeh/workspace/secret token=abcdef123456 "
                    + "Authorization: Bearer abc.def SELECT * FROM knowledge_page RID #12:0 "
                    + "{\"error\":{\"message\":\"upstream\"}}";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void duplicateWorkspaceNeverExposesTheRootPath() {
        DuplicateWorkspaceException exception = new DuplicateWorkspaceException(
                "/Users/toddyeh/workspace/secret-project", 7L);
        ResponseEntity<ApiError> response = handler.handleDuplicateWorkspace(exception);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(response)).isEqualTo("WORKSPACE_ALREADY_EXISTS");
        assertThat(message(response))
                .isEqualTo("A workspace already exists for this root path")
                .doesNotContain("/Users", "toddyeh", "secret");
    }

    @Test
    void illegalArgumentRedactsHostileMessagesAndKeepsSafeValidationWording() {
        ResponseEntity<ApiError> hostile = handler.handleIllegalArgument(
                new IllegalArgumentException(HOSTILE));
        assertThat(hostile.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(hostile)).isEqualTo("INVALID_REQUEST");
        assertThat(message(hostile)).isEqualTo("Request validation failed");

        ResponseEntity<ApiError> safe = handler.handleIllegalArgument(
                new IllegalArgumentException("size must be between 1 and 200"));
        assertThat(message(safe)).isEqualTo("size must be between 1 and 200");
        assertThat(safe.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void notFoundAndConflictHandlersKeepStableCodesAndRedactMessages() {
        ResponseEntity<ApiError> document = handler.handleDocumentNotFound(
                new DocumentNotFoundException(42L));
        assertThat(document.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(document)).isEqualTo("DOCUMENT_NOT_FOUND");
        assertThat(message(document)).isEqualTo("Document not found: 42");

        ResponseEntity<ApiError> wikiLifecycle = handler.handleWikiDraftLifecycle(
                new WikiDraftLifecycleException(HOSTILE));
        assertThat(wikiLifecycle.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(wikiLifecycle)).isEqualTo("WIKI_DRAFT_LIFECYCLE_CONFLICT");
        assertThat(message(wikiLifecycle))
                .isEqualTo("Wiki draft is in a conflicting lifecycle state");

        ResponseEntity<ApiError> wikiPublish = handler.handleWikiPublish(
                new WikiPublishException(WikiPublishException.Reason.FILESYSTEM_FAILURE,
                        HOSTILE));
        assertThat(wikiPublish.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(code(wikiPublish)).isEqualTo("WIKI_PUBLISH_FILESYSTEM_FAILURE");
        assertThat(message(wikiPublish)).isEqualTo("Wiki publish operation failed");
    }

    @Test
    void extractionHandlerKeepsItsErrorCodeAndRedactsTheMessage() {
        ResponseEntity<ApiError> response = handler.handleDocumentExtraction(
                new DocumentExtractionException("EXTRACTION_PARSE_FAILED", HOSTILE));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(code(response)).isEqualTo("EXTRACTION_PARSE_FAILED");
        assertThat(message(response)).isEqualTo("Document extraction failed");
    }

    @Test
    void retrievalUnavailableKeepsItsTypedMessage() {
        ResponseEntity<ApiError> response = handler.handleRetrievalUnavailable(
                new RetrievalUnavailableException(RetrievalUnavailableException.Dependency.GRAPH,
                        new IllegalStateException("backend unavailable")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(code(response)).isEqualTo("RETRIEVAL_UNAVAILABLE");
        assertThat(message(response)).isEqualTo("Retrieval dependency is unavailable: GRAPH");
    }

    @Test
    void graphAndAskTypedMappingsStayUntouched() {
        ResponseEntity<ApiError> graph = handler.handleGraphProjection(
                new GraphProjectionException(new GraphProjectionFailure(
                        GraphProjectionFailureType.BACKEND_LOCKED, HOSTILE)));
        assertThat(graph.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(code(graph)).isEqualTo("GRAPH_BACKEND_LOCKED");
        assertThat(message(graph)).isEqualTo("Graph projection operation failed");

        ResponseEntity<ApiError> ask = handler.handleAskFailure(new AskApiException(
                AskFailureType.PROVIDER_TIMEOUT_OR_NETWORK_UNAVAILABLE));
        assertThat(ask.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(code(ask)).isEqualTo(AskFailureType.PROVIDER_TIMEOUT_OR_NETWORK_UNAVAILABLE
                .publicCode());
        assertThat(message(ask)).isEqualTo("Answer provider is unavailable");
    }

    private static String code(ResponseEntity<ApiError> response) {
        return response.getBody().error().code();
    }

    private static String message(ResponseEntity<ApiError> response) {
        return response.getBody().error().message();
    }
}
