package org.km.llmwiki.web;

import org.km.llmwiki.ai.ask.AskApiException;
import org.km.llmwiki.ai.ask.AskFailureType;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.rag.RetrievalUnavailableException;
import org.km.llmwiki.search.embedding.ProcessingJobNotFoundException;
import org.km.llmwiki.source.DocumentAlreadyProcessedException;
import org.km.llmwiki.source.DocumentExtractionException;
import org.km.llmwiki.source.DocumentNotFoundException;
import org.km.llmwiki.source.SourceChunkNotFoundException;
import org.km.llmwiki.wiki.KnowledgeProposalNotFoundException;
import org.km.llmwiki.wiki.WikiDraftLifecycleException;
import org.km.llmwiki.wiki.WikiDraftNotFoundException;
import org.km.llmwiki.wiki.WikiDraftTargetException;
import org.km.llmwiki.wiki.WikiPublishException;
import org.km.llmwiki.workspace.DuplicateWorkspaceException;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Public REST error boundary: stable codes and HTTP statuses are decided by the typed
 * exception, and every message is an {@link DiagnosticRedaction} operator-safe projection —
 * the raw exception message never crosses the REST boundary by default. Curated messages
 * that survive redaction (safe resource identifiers, validation wording) travel to the
 * caller; anything carrying a path, secret, SQL fragment, or backend identity collapses to
 * the type's fixed fallback. The full exception and its cause chain stay server-side in the
 * application log.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                DiagnosticRedaction.publicMessage(exception.getMessage(),
                        "Request validation failed"), exception);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body is not readable", exception);
    }

    @ExceptionHandler(DuplicateWorkspaceException.class)
    public ResponseEntity<ApiError> handleDuplicateWorkspace(DuplicateWorkspaceException exception) {
        // The raw message embeds the workspace root path; the public projection stays fixed.
        return respond(HttpStatus.CONFLICT, "WORKSPACE_ALREADY_EXISTS",
                "A workspace already exists for this root path", exception);
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ApiError> handleDocumentNotFound(DocumentNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", publicMessage(exception), exception);
    }

    @ExceptionHandler(SourceChunkNotFoundException.class)
    public ResponseEntity<ApiError> handleSourceChunkNotFound(SourceChunkNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "SOURCE_CHUNK_NOT_FOUND", publicMessage(exception), exception);
    }

    @ExceptionHandler(KnowledgeProposalNotFoundException.class)
    public ResponseEntity<ApiError> handleKnowledgeProposalNotFound(
            KnowledgeProposalNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "KNOWLEDGE_PROPOSAL_NOT_FOUND",
                publicMessage(exception), exception);
    }

    @ExceptionHandler(WikiDraftNotFoundException.class)
    public ResponseEntity<ApiError> handleWikiDraftNotFound(WikiDraftNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "WIKI_DRAFT_NOT_FOUND", publicMessage(exception), exception);
    }

    @ExceptionHandler(WikiDraftLifecycleException.class)
    public ResponseEntity<ApiError> handleWikiDraftLifecycle(WikiDraftLifecycleException exception) {
        return respond(HttpStatus.CONFLICT, "WIKI_DRAFT_LIFECYCLE_CONFLICT",
                publicMessage(exception, "Wiki draft is in a conflicting lifecycle state"), exception);
    }

    @ExceptionHandler(WikiDraftTargetException.class)
    public ResponseEntity<ApiError> handleWikiDraftTarget(WikiDraftTargetException exception) {
        return respond(HttpStatus.CONFLICT, "WIKI_DRAFT_TARGET_" + exception.reason().name(),
                publicMessage(exception, "Wiki draft target validation failed"), exception);
    }

    @ExceptionHandler(WikiPublishException.class)
    public ResponseEntity<ApiError> handleWikiPublish(WikiPublishException exception) {
        HttpStatus status = switch (exception.reason()) {
            case FILESYSTEM_FAILURE, CONTENT_VALIDATION_FAILED, METADATA_FAILURE, RECONCILIATION_REQUIRED ->
                    HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.CONFLICT;
        };
        return respond(status, "WIKI_PUBLISH_" + exception.reason().name(),
                publicMessage(exception, "Wiki publish operation failed"), exception);
    }

    @ExceptionHandler(DocumentAlreadyProcessedException.class)
    public ResponseEntity<ApiError> handleDocumentAlreadyProcessed(
            DocumentAlreadyProcessedException exception) {
        return respond(HttpStatus.CONFLICT, "DOCUMENT_ALREADY_PROCESSED", publicMessage(exception), exception);
    }

    @ExceptionHandler(DocumentExtractionException.class)
    public ResponseEntity<ApiError> handleDocumentExtraction(DocumentExtractionException exception) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, exception.errorCode(),
                publicMessage(exception, "Document extraction failed"), exception);
    }

    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ResponseEntity<ApiError> handleWorkspaceNotFound(WorkspaceNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND", publicMessage(exception), exception);
    }

    @ExceptionHandler(NoActiveWorkspaceException.class)
    public ResponseEntity<ApiError> handleNoActiveWorkspace(NoActiveWorkspaceException exception) {
        return respond(HttpStatus.NOT_FOUND, "NO_ACTIVE_WORKSPACE", publicMessage(exception), exception);
    }

    @ExceptionHandler(ProcessingJobNotFoundException.class)
    public ResponseEntity<ApiError> handleProcessingJobNotFound(ProcessingJobNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "PROCESSING_JOB_NOT_FOUND", publicMessage(exception), exception);
    }

    @ExceptionHandler(RetrievalUnavailableException.class)
    public ResponseEntity<ApiError> handleRetrievalUnavailable(
            RetrievalUnavailableException exception) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "RETRIEVAL_UNAVAILABLE",
                publicMessage(exception, "Retrieval service is unavailable"), exception);
    }

    /**
     * Typed graph projection failure taxonomy for the operational API. Refused-state failures
     * stay 409, operational infrastructure failures stay 503, and integrity/correctness
     * violations fail closed as 500 — corruption is never disguised as a retryable
     * unavailability. Only the stable public code leaves the process; raw causes stay
     * server-side.
     */
    @ExceptionHandler(GraphProjectionException.class)
    public ResponseEntity<ApiError> handleGraphProjection(GraphProjectionException exception) {
        GraphProjectionFailureType type = exception.failureType();
        HttpStatus status = switch (type) {
            case CAPABILITY_DISABLED, CONFIGURATION_INVALID, PROJECTION_NOT_READY,
                    PROJECTION_STALE, PROJECTION_INCOMPATIBLE -> HttpStatus.CONFLICT;
            case CAPABILITY_UNAVAILABLE, BACKEND_LOCKED, FILESYSTEM_UNAVAILABLE,
                    TRANSACTION_FAILURE, BACKEND_FAILURE -> HttpStatus.SERVICE_UNAVAILABLE;
            case PROJECTION_CORRUPT, INVALID_PROJECTION_INPUT, INVALID_PROVENANCE,
                    CROSS_WORKSPACE, INVALID_TRAVERSAL_BOUNDS, LOCAL_VALIDATION ->
                    HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return respond(status, type.publicCode(), "Graph projection operation failed", exception);
    }

    @ExceptionHandler(AskApiException.class)
    public ResponseEntity<ApiError> handleAskFailure(AskApiException exception) {
        AskFailureType type = exception.failureType();
        HttpStatus status = switch (type) {
            case LOCAL_VALIDATION -> HttpStatus.BAD_REQUEST;
            case PROVIDER_INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
            case RETRIEVAL_UNAVAILABLE,
                    RETRIEVAL_VECTOR_UNAVAILABLE,
                    PROVIDER_CONFIGURATION_UNAVAILABLE,
                    PROVIDER_AUTHENTICATION_OR_AUTHORIZATION,
                    PROVIDER_RATE_LIMIT_OR_QUOTA,
                    PROVIDER_TIMEOUT_OR_NETWORK_UNAVAILABLE,
                    PROVIDER_SERVER_FAILURE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String message = switch (type) {
            case RETRIEVAL_UNAVAILABLE -> "Retrieval service is unavailable";
            case RETRIEVAL_VECTOR_UNAVAILABLE -> "Semantic retrieval is unavailable";
            case PROVIDER_CONFIGURATION_UNAVAILABLE -> "Answer provider is not configured";
            case PROVIDER_AUTHENTICATION_OR_AUTHORIZATION ->
                    "Answer provider authentication failed";
            case PROVIDER_RATE_LIMIT_OR_QUOTA -> "Answer provider is rate limited";
            case PROVIDER_TIMEOUT_OR_NETWORK_UNAVAILABLE -> "Answer provider is unavailable";
            case PROVIDER_SERVER_FAILURE -> "Answer provider failed";
            case PROVIDER_INVALID_RESPONSE -> "Answer provider returned an invalid response";
            case LOCAL_VALIDATION -> "Ask request was rejected";
        };
        return respond(status, type.publicCode(), message, exception);
    }

    @ExceptionHandler({NoResourceFoundException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleNotFound(Exception exception) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", exception);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException exception) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error", exception);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error", exception);
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, String code, String message,
                                                    Exception exception) {
        // The full cause chain stays server-side; only the sanitized projection responds.
        log.debug("REST error mapped to {} ({}): {}", code, status, message, exception);
        return ResponseEntity.status(status).body(ApiError.of(code, message));
    }

    private static String publicMessage(Exception exception) {
        return DiagnosticRedaction.publicMessage(exception.getMessage(), "Request failed");
    }

    private static String publicMessage(Exception exception, String fallback) {
        return DiagnosticRedaction.publicMessage(exception.getMessage(), fallback);
    }
}
