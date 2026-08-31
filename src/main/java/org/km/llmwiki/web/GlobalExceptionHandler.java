package org.km.llmwiki.web;

import org.km.llmwiki.rag.RetrievalUnavailableException;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body is not readable");
    }

    @ExceptionHandler(DuplicateWorkspaceException.class)
    public ResponseEntity<ApiError> handleDuplicateWorkspace(DuplicateWorkspaceException exception) {
        return respond(HttpStatus.CONFLICT, "WORKSPACE_ALREADY_EXISTS", exception.getMessage());
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ApiError> handleDocumentNotFound(DocumentNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(SourceChunkNotFoundException.class)
    public ResponseEntity<ApiError> handleSourceChunkNotFound(SourceChunkNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "SOURCE_CHUNK_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(KnowledgeProposalNotFoundException.class)
    public ResponseEntity<ApiError> handleKnowledgeProposalNotFound(KnowledgeProposalNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "KNOWLEDGE_PROPOSAL_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(WikiDraftNotFoundException.class)
    public ResponseEntity<ApiError> handleWikiDraftNotFound(WikiDraftNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "WIKI_DRAFT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(WikiDraftLifecycleException.class)
    public ResponseEntity<ApiError> handleWikiDraftLifecycle(WikiDraftLifecycleException exception) {
        return respond(HttpStatus.CONFLICT, "WIKI_DRAFT_LIFECYCLE_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(WikiDraftTargetException.class)
    public ResponseEntity<ApiError> handleWikiDraftTarget(WikiDraftTargetException exception) {
        return respond(HttpStatus.CONFLICT, "WIKI_DRAFT_TARGET_" + exception.reason().name(),
                exception.getMessage());
    }

    @ExceptionHandler(WikiPublishException.class)
    public ResponseEntity<ApiError> handleWikiPublish(WikiPublishException exception) {
        HttpStatus status = switch (exception.reason()) {
            case FILESYSTEM_FAILURE, CONTENT_VALIDATION_FAILED, METADATA_FAILURE, RECONCILIATION_REQUIRED ->
                    HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.CONFLICT;
        };
        return respond(status, "WIKI_PUBLISH_" + exception.reason().name(), exception.getMessage());
    }

    @ExceptionHandler(DocumentAlreadyProcessedException.class)
    public ResponseEntity<ApiError> handleDocumentAlreadyProcessed(DocumentAlreadyProcessedException exception) {
        return respond(HttpStatus.CONFLICT, "DOCUMENT_ALREADY_PROCESSED", exception.getMessage());
    }

    @ExceptionHandler(DocumentExtractionException.class)
    public ResponseEntity<ApiError> handleDocumentExtraction(DocumentExtractionException exception) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, exception.errorCode(), exception.getMessage());
    }

    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ResponseEntity<ApiError> handleWorkspaceNotFound(WorkspaceNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(NoActiveWorkspaceException.class)
    public ResponseEntity<ApiError> handleNoActiveWorkspace(NoActiveWorkspaceException exception) {
        return respond(HttpStatus.NOT_FOUND, "NO_ACTIVE_WORKSPACE", exception.getMessage());
    }

    @ExceptionHandler(RetrievalUnavailableException.class)
    public ResponseEntity<ApiError> handleRetrievalUnavailable(
            RetrievalUnavailableException exception) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "RETRIEVAL_UNAVAILABLE",
                exception.getMessage());
    }

    @ExceptionHandler({NoResourceFoundException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleNotFound(Exception exception) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException exception) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", exception.getMessage());
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiError.of(code, message));
    }
}
